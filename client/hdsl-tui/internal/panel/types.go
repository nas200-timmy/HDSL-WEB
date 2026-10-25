// Package panel is the HTTP half of the terminal client: login, developer
// elevation, instance lists and opening/closing a terminal. It speaks the
// REST contract documented in docs/terminal-protocol.md and nothing else.
package panel

import "time"

// Health is the body of GET /api/health.
type Health struct {
	Status    string `json:"status"`
	Version   string `json:"version"`
	UptimeSec int64  `json:"uptimeSec"`
}

// Instance is one entry of GET /api/instances or
// GET /api/brands/{brand}/instances. Both endpoints use the same shape.
type Instance struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	State string `json:"state"`
}

type instanceList struct {
	Instances []Instance `json:"instances"`
}

// Terminal is the body of POST .../terminal. Mode is "pty" (Ticket and WS are
// set) or "desktop" (the panel runs on Windows and has no PTY to attach to;
// Hint explains that to the user).
type Terminal struct {
	Mode     string `json:"mode"`
	Protocol int    `json:"protocol"`
	Instance string `json:"instance"`
	Ticket   string `json:"ticket"`
	WS       string `json:"ws"`
	Brand    string `json:"brand"`
	Hint     string `json:"hint"`
}

// Kernel is one terminal kernel a surface offers. Only Claude Web has more than
// one: the community engine its browser-side provider configuration is written
// for, and the official Claude Code binary.
type Kernel struct {
	ID        string `json:"id"`
	Label     string `json:"label"`
	Available bool   `json:"available"`
	Detail    string `json:"detail"`
}

type kernelList struct {
	Kernels []Kernel `json:"kernels"`
	Default *string  `json:"default"`
}

// Desktop reports whether the panel answered with a desktop console instead of
// a PTY.
func (t Terminal) Desktop() bool { return t.Mode == "desktop" }

// DeveloperStatus is the body of GET /api/developer/status: what the panel holds
// right now, read before a terminal is opened so the developer password is asked
// for in the order the web UI uses.
type DeveloperStatus struct {
	DevPasswordSet  bool `json:"devPasswordSet"`
	Elevated        bool `json:"elevated"`
	WebShellEnabled bool `json:"webShellEnabled"`
	HTTPS           bool `json:"https"`
}

// Elevation is the body of POST /api/developer/elevate.
type Elevation struct {
	Elevated  bool   `json:"elevated"`
	ExpiresAt string `json:"expiresAt"`
}

// Expiry parses ExpiresAt; a panel that omits it yields the zero time.
func (e Elevation) Expiry() time.Time {
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339, "2006-01-02T15:04:05.999999999"} {
		if parsed, err := time.Parse(layout, e.ExpiresAt); err == nil {
			return parsed
		}
	}
	return time.Time{}
}

type loginBody struct {
	OK       bool   `json:"ok"`
	Username string `json:"username"`
}

type closeBody struct {
	OK     bool `json:"ok"`
	Killed bool `json:"killed"`
}
