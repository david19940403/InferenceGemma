package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"golang.org/x/crypto/ssh"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestDailyToolsOverPinnedSSH(t *testing.T) {
	path := os.Getenv("DAILY_SSH_TEST_CONFIG")
	if path == "" {
		t.Skip("Set DAILY_SSH_TEST_CONFIG for the local PC integration")
	}
	var config struct {
		PrivateKey string `json:"private_key"`
		KnownHosts string `json:"known_hosts"`
		Hosts      []struct {
			Host string `json:"host"`
			Port int    `json:"port"`
			User string `json:"user"`
		} `json:"hosts"`
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &config); err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.ParsePrivateKey([]byte(config.PrivateKey))
	if err != nil {
		t.Fatal("Invalid local client key")
	}
	fields := strings.Fields(config.KnownHosts)
	if len(fields) < 3 {
		t.Fatal("Missing pinned host key")
	}
	encoded, err := base64.StdEncoding.DecodeString(fields[2])
	if err != nil {
		t.Fatal(err)
	}
	public, err := ssh.ParsePublicKey(encoded)
	if err != nil {
		t.Fatal(err)
	}
	host := config.Hosts[0]
	client, err := ssh.Dial("tcp", net.JoinHostPort(host.Host, strconv.Itoa(host.Port)), &ssh.ClientConfig{User: host.User, Auth: []ssh.AuthMethod{ssh.PublicKeys(signer)}, HostKeyCallback: ssh.FixedHostKey(public), Timeout: 5 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	script, err := filepath.Abs("../../remote-scripts/windows/daily-tools.ps1")
	if err != nil {
		t.Fatal(err)
	}
	cases := [][]string{{"vpn"}, {"git", "inferencegemma"}, {"adb"}, {"servicios"}, {"imagenes", "productos"}, {"n8n", "aperturas_takepos"}, {"apks", "inferencegemma"}}
	for _, args := range cases {
		t.Run(args[0], func(t *testing.T) {
			session, e := client.NewSession()
			if e != nil {
				t.Fatal(e)
			}
			defer session.Close()
			var out, stderr bytes.Buffer
			session.Stdout = &out
			session.Stderr = &stderr
			if e = session.Run(command(script, args...)); e != nil {
				t.Fatalf("Read-only tool failed: %s", args[0])
			}
			var result map[string]any
			if e = json.Unmarshal(out.Bytes(), &result); e != nil {
				t.Fatal("Invalid tool JSON")
			}
			if result["status"] != "success" || len(strings.TrimSpace(out.String())) > 400 {
				t.Fatal("Invalid or oversized summary")
			}
		})
	}
	session, err := client.NewSession()
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if err = session.Run(command(script, "git", "../private")); err == nil {
		t.Fatal("Unapproved path accepted")
	}
}
