package main

import (
	"bytes"
	"fmt"
	"strings"
	"testing"

	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/cli"
	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/pterm"
)

func TestVersionPrintsAndDoesNotTouchTheNetwork(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := run([]string{"--version"}, strings.NewReader(""), &stdout, &stderr)
	if code != cli.ExitOK {
		t.Fatalf("exit code = %d, want 0", code)
	}
	if !strings.Contains(stdout.String(), version) {
		t.Errorf("stdout = %q, want the version", stdout.String())
	}
	if !strings.Contains(stdout.String(), fmt.Sprintf("terminal protocol %d", pterm.Protocol)) {
		t.Errorf("stdout = %q, want the protocol number", stdout.String())
	}
	if stderr.Len() != 0 {
		t.Errorf("stderr = %q, want nothing", stderr.String())
	}
}

func TestUsageErrors(t *testing.T) {
	cases := []struct {
		name string
		args []string
	}{
		{"no panel address", []string{}},
		{"unknown brand", []string{"--brand", "vscode", "https://panel.test"}},
		{"address without a scheme", []string{"panel.example.com"}},
		{"address without a host", []string{"https://"}},
		{"two addresses", []string{"https://a.test", "https://b.test"}},
		{"unknown flag", []string{"--nope", "https://panel.test"}},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			code := run(testCase.args, strings.NewReader(""), &stdout, &stderr)
			if code != cli.ExitUsage {
				t.Fatalf("exit code = %d, want %d (stderr %q)", code, cli.ExitUsage, stderr.String())
			}
			if !strings.Contains(stderr.String(), "用法:") {
				t.Errorf("stderr does not explain the usage:\n%s", stderr.String())
			}
		})
	}
}

func TestHelpExitsCleanly(t *testing.T) {
	var stdout, stderr bytes.Buffer
	if code := run([]string{"--help"}, strings.NewReader(""), &stdout, &stderr); code != cli.ExitOK {
		t.Fatalf("exit code = %d, want 0", code)
	}
	if !strings.Contains(stdout.String(), "退出码:") {
		t.Errorf("help does not list the exit codes:\n%s", stdout.String())
	}
	if !strings.Contains(stdout.String(), "用法:") {
		t.Errorf("help does not show the usage:\n%s", stdout.String())
	}
}

func TestBrandNormalisation(t *testing.T) {
	if got := normaliseBrand("dsh"); got != "" {
		t.Errorf("normaliseBrand(dsh) = %q, want the DSH instance surface", got)
	}
	if got := normaliseBrand("kimi"); got != "kimi" {
		t.Errorf("normaliseBrand(kimi) = %q", got)
	}
	if got := normaliseBrand(""); got != "" {
		t.Errorf("normaliseBrand(\"\") = %q", got)
	}
}

func TestParseArgsAcceptsTheDocumentedForm(t *testing.T) {
	opts, base, err := parseArgs([]string{
		"--instance", "dsh-1", "--brand", "kimi", "--resume", "s-9",
		"--user", "admin", "--ca", "/tmp/ca.pem", "--insecure", "--verbose",
		"https://panel.test:8443/",
	})
	if err != nil {
		t.Fatalf("parseArgs: %v", err)
	}
	if opts.instance != "dsh-1" || opts.brand != "kimi" || opts.resume != "s-9" ||
		opts.user != "admin" || opts.ca != "/tmp/ca.pem" || !opts.insecure || !opts.verbose {
		t.Errorf("options = %+v", opts)
	}
	if base.String() != "https://panel.test:8443" {
		t.Errorf("base = %q, the trailing slash should be trimmed", base.String())
	}
}
