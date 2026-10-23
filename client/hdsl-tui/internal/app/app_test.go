package app

import (
	"net/http"
	"strings"
	"testing"

	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/panel"
	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/ui"
)

func TestWrapHonoursWidthAndNewlines(t *testing.T) {
	lines := wrap("一二三四五六七八九十", 8)
	for _, line := range lines {
		if ui.VisibleWidth(line) > 8 {
			t.Errorf("line %q is %d cells", line, ui.VisibleWidth(line))
		}
	}
	if len(lines) < 2 {
		t.Errorf("lines = %v, want a wrap", lines)
	}
	if got := wrap("a\n\nb", 10); len(got) != 3 {
		t.Errorf("wrap kept %d lines, want 3", len(got))
	}
}

func TestNormaliseAddress(t *testing.T) {
	cases := []struct {
		input string
		want  string
		bad   bool
	}{
		{"https://panel.example.com:8443/", "https://panel.example.com:8443", false},
		{"panel.example.com:8443", "https://panel.example.com:8443", false},
		{"http://127.0.0.1:8080", "http://127.0.0.1:8080", false},
		{"https://panel.test/base/", "https://panel.test/base", false},
		{"", "", true},
		{"https://", "", true},
		{"ftp://panel.test", "", true},
	}
	for _, testCase := range cases {
		got, err := normaliseAddress(testCase.input)
		if testCase.bad {
			if err == nil {
				t.Errorf("normaliseAddress(%q) = %q, want an error", testCase.input, got)
			}
			continue
		}
		if err != nil {
			t.Errorf("normaliseAddress(%q): %v", testCase.input, err)
			continue
		}
		if got != testCase.want {
			t.Errorf("normaliseAddress(%q) = %q, want %q", testCase.input, got, testCase.want)
		}
	}
}

func TestStateText(t *testing.T) {
	cases := map[string]string{
		"running":  "运行中",
		"stopped":  "已停止",
		"starting": "启动中",
		"failed":   "失败",
		"oddball":  "oddball",
		"":         "状态未知",
	}
	for state, want := range cases {
		if got := stateText(state); got != want {
			t.Errorf("stateText(%q) = %q, want %q", state, got, want)
		}
	}
}

func TestExplainMapsPanelCodesToAdvice(t *testing.T) {
	cases := []struct {
		err  error
		want string
	}{
		{
			&panel.APIError{Status: 403, Message: "developer_elevation_required: nope", Code: "developer_elevation_required"},
			"开发者密码",
		},
		{&panel.APIError{Status: 403, Message: "web_shell_disabled: off", Code: "web_shell_disabled"}, "Web Shell"},
		{&panel.APIError{Status: 403, Message: "insecure_transport: no", Code: "insecure_transport"}, "HTTPS"},
		{&panel.APIError{Status: 400, Message: "invalid username or password", Code: "invalid username or password"}, "invalid username or password"},
	}
	for _, testCase := range cases {
		if got := explain(testCase.err); !strings.Contains(got, testCase.want) {
			t.Errorf("explain(%v) = %q, want it to mention %q", testCase.err, got, testCase.want)
		}
	}
}

func TestNeedsElevation(t *testing.T) {
	required := &panel.APIError{Status: 403, Message: "developer_elevation_required: x", Code: "developer_elevation_required"}
	if !needsElevation(required) {
		t.Error("needsElevation missed the code")
	}
	plain := &panel.APIError{Status: 403, Message: "web_shell_disabled: x", Code: "web_shell_disabled"}
	if needsElevation(plain) {
		t.Error("needsElevation fired on an unrelated refusal")
	}
}

func TestInstanceLabelFallsBackToTheID(t *testing.T) {
	if got := instanceLabel(panel.Instance{ID: "abc"}); got != "abc" {
		t.Errorf("instanceLabel = %q", got)
	}
	if got := instanceLabel(panel.Instance{ID: "abc", Name: "名字"}); got != "名字" {
		t.Errorf("instanceLabel = %q", got)
	}
}

func TestExplainLeavesNonPanelErrorsAlone(t *testing.T) {
	if got := explain(nil); got != "" {
		t.Errorf("explain(nil) = %q", got)
	}
	err := &panel.TransportError{Op: "GET /api/health", Err: http.ErrServerClosed}
	if got := explain(err); !strings.Contains(got, "网络错误") {
		t.Errorf("explain(transport) = %q", got)
	}
}
