package pterm

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	// handshakeTimeout is how long the upgrade itself may take.
	handshakeTimeout = 15 * time.Second
	// readLimit bounds one message. A PTY can emit a lot at once (`cat` of a
	// large file, a build log); the default 32 KiB would kill the connection.
	readLimit = 8 << 20
	// stdinChunk is how much of stdin is forwarded per binary frame.
	stdinChunk = 32 << 10
)

// Conn is one attached terminal WebSocket.
type Conn struct {
	ws     *websocket.Conn
	writeM sync.Mutex
}

// Dial attaches to the panel. cookie is the `hdsl_session` cookie value; the
// WebSocket handshake has to carry it, because the ticket alone only proves
// that *someone* opened a terminal.
func Dial(ctx context.Context, url, cookie string, tlsConfig *tls.Config) (*Conn, error) {
	dialer := &websocket.Dialer{
		HandshakeTimeout: handshakeTimeout,
		TLSClientConfig:  tlsConfig,
	}
	header := http.Header{}
	if cookie != "" {
		header.Set("Cookie", cookie)
	}
	conn, response, err := dialer.DialContext(ctx, url, header)
	if err != nil {
		if response != nil {
			return nil, &CloseError{Code: response.StatusCode, Reason: response.Status}
		}
		return nil, fmt.Errorf("无法连接终端 WebSocket: %w", err)
	}
	conn.SetReadLimit(readLimit)
	return &Conn{ws: conn}, nil
}

// CloseError reports a close frame (or a refused upgrade) with its code.
type CloseError struct {
	Code   int
	Reason string
}

func (e *CloseError) Error() string {
	return fmt.Sprintf("连接已关闭 (%d %s)", e.Code, e.Reason)
}

// SendBinary writes raw terminal input.
func (c *Conn) SendBinary(data []byte) error {
	c.writeM.Lock()
	defer c.writeM.Unlock()
	return c.ws.WriteMessage(websocket.BinaryMessage, data)
}

// SendText writes a control frame.
func (c *Conn) SendText(data []byte) error {
	c.writeM.Lock()
	defer c.writeM.Unlock()
	return c.ws.WriteMessage(websocket.TextMessage, data)
}

// SendResize tells the agent how big the local terminal is.
func (c *Conn) SendResize(cols, rows int) error {
	frame, err := EncodeResize(cols, rows)
	if err != nil {
		return err
	}
	return c.SendText(frame)
}

// Close closes the socket. A close only detaches; the agent keeps running.
func (c *Conn) Close() error {
	return c.ws.Close()
}

// Command is what a local key sequence asked for, as opposed to what the agent
// did.
type Command int

const (
	// CommandNone means the session ended on its own.
	CommandNone Command = iota
	// CommandDetach leaves the agent running and quits the client.
	CommandDetach
	// CommandKill stops the terminal and quits the client.
	CommandKill
)

// Filter inspects stdin on the way to the agent. It returns the bytes to
// forward (nil forwards nothing) and, when the chunk contains a local command,
// that command — the rest of the chunk is then dropped.
//
// The detach state machine behind it lives in internal/cli; keeping it a hook
// here is what lets the pump be exercised without a real terminal.
type Filter func(chunk []byte) (forward []byte, command Command)

// Options configures a session run.
type Options struct {
	In      io.Reader
	Out     io.Writer
	Logf    func(format string, args ...any)
	OnReady func(Control)
	Filter  Filter
}

func (o Options) logf(format string, args ...any) {
	if o.Logf != nil {
		o.Logf(format, args...)
	}
}

// Result is how a session ended.
type Result struct {
	// ExitKnown is true when the agent sent an `exit` frame; ExitCode is then
	// the agent's own exit status.
	ExitKnown bool
	ExitCode  int
	// Command is set when the local user detached or killed the terminal.
	Command Command
	// CloseCode and CloseReason describe the close frame, if any.
	CloseCode   int
	CloseReason string
}

// Run pumps bytes until the agent exits, the socket closes, the user detaches,
// or ctx is cancelled. The caller owns closing the socket and restoring the
// terminal.
func (c *Conn) Run(ctx context.Context, opts Options) (Result, error) {
	if opts.In == nil {
		opts.In = emptyReader{}
	}
	if opts.Out == nil {
		opts.Out = io.Discard
	}
	done := make(chan struct{})
	defer close(done)
	outcomes := make(chan Result, 1)
	failures := make(chan error, 1)
	publish := func(result Result) {
		select {
		case outcomes <- result:
		case <-done:
		}
	}
	fail := func(err error) {
		select {
		case failures <- err:
		case <-done:
		}
	}

	go func() {
		if err := c.readLoop(opts, publish); err != nil {
			fail(err)
		}
	}()

	// A closed stdin (EOF) stops input but must not end the session: the agent
	// may still have output, and the socket is the only way to see it.
	go func() {
		buffer := make([]byte, stdinChunk)
		for {
			n, err := opts.In.Read(buffer)
			if n > 0 {
				forward, command := buffer[:n], CommandNone
				if opts.Filter != nil {
					forward, command = opts.Filter(buffer[:n])
				}
				if len(forward) > 0 {
					if sendErr := c.SendBinary(forward); sendErr != nil {
						fail(sendErr)
						return
					}
				}
				if command != CommandNone {
					publish(Result{Command: command})
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()

	select {
	case result := <-outcomes:
		return result, nil
	case err := <-failures:
		return Result{}, err
	case <-ctx.Done():
		return Result{}, ctx.Err()
	}
}

// readLoop consumes frames until something ends the session. It returns the
// read error (nil when the session ended through a frame or a close) and
// publishes a terminal result.
func (c *Conn) readLoop(opts Options, publish func(Result)) error {
	for {
		kind, data, err := c.ws.ReadMessage()
		if err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			var closeErr *websocket.CloseError
			if errors.As(err, &closeErr) {
				publish(Result{CloseCode: closeErr.Code, CloseReason: closeErr.Text})
				return nil
			}
			return err
		}
		switch kind {
		case websocket.BinaryMessage:
			if _, err := opts.Out.Write(data); err != nil {
				return err
			}
		case websocket.TextMessage:
			control, err := DecodeControl(data)
			if err != nil {
				opts.logf("忽略无法解析的控制帧: %v", err)
				continue
			}
			switch control.Type {
			case TypeReady:
				if opts.OnReady != nil {
					opts.OnReady(control)
				}
			case TypeExit:
				publish(Result{ExitKnown: true, ExitCode: control.Code})
				return nil
			default:
				opts.logf("忽略未知控制帧 %q", control.Type)
			}
		}
	}
}

type emptyReader struct{}

func (emptyReader) Read([]byte) (int, error) { return 0, io.EOF }
