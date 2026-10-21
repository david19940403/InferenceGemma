package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"os"
	"path/filepath"
	"testing"
)

func TestResolvesOnlyFixedScriptsAndIPArguments(t *testing.T) {
	status := `C:\repo\system_status.ps1`
	ping := `C:\repo\test-ping.ps1`
	for _, ip := range []string{"127.0.0.1", "::1", "192.0.2.1"} {
		script, args, ok := resolve(command(ping, ip), status, ping)
		if !ok || script != ping || len(args) != 2 || args[0] != "-Destination" || args[1] != ip {
			t.Fatalf("valid IP rejected: %s", ip)
		}
	}
	script, args, ok := resolve(command(status), status, ping)
	if !ok || script != status || len(args) != 0 {
		t.Fatal("status rejected")
	}
	for _, wire := range []string{command(ping), command(ping, "127.0.0.1", "more"), command(ping, "127.0.0.1'; Remove-Item C:\\ -Recurse; '"), command(ping, "example.com"), command(`C:\repo\other.ps1`, "127.0.0.1"), command(status, "127.0.0.1"), commandPrefix + "%%%", "powershell.exe -Command whoami"} {
		if _, _, ok := resolve(wire, status, ping); ok {
			t.Fatal("unapproved command accepted")
		}
	}
}
func TestKeepsPinnedHostIdentityAndClientCredentialsOnRestart(t *testing.T) {
	dir := t.TempDir()
	_, firstKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	first, err := persistedHostSigner(dir, firstKey)
	if err != nil {
		t.Fatal(err)
	}
	_, otherKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	second, err := persistedHostSigner(dir, otherKey)
	if err != nil {
		t.Fatal(err)
	}
	if string(first.PublicKey().Marshal()) != string(second.PublicKey().Marshal()) {
		t.Fatal("host key rotated unexpectedly")
	}
	if err = os.WriteFile(filepath.Join(dir, "smoke-config.json"), []byte(`{"private_key":"existing-test-identity","token":"existing-test-token"}`), 0600); err != nil {
		t.Fatal(err)
	}
	key, token, err := reuseClient(dir, "replacement-key", "replacement-token")
	if err != nil {
		t.Fatal(err)
	}
	if key != "existing-test-identity" || token != "existing-test-token" {
		t.Fatal("client credentials rotated unexpectedly")
	}
}
