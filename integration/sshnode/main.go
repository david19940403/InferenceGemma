// Local-only hardware smoke fixture: authenticates a generated Ed25519 client and
// permits fixed read-only status, IP ping and approved daily diagnostics. It never executes received shell text.
package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"unicode/utf16"

	"golang.org/x/crypto/ssh"
)

func main() {
	dir := flag.String("dir", "../../.cache/device-test", "Private fixture directory")
	script := flag.String("script", "../../remote-scripts/windows/system_status.ps1", "Only allowed script")
	ping := flag.String("ping-script", "../../remote-scripts/windows/test-ping.ps1", "Allowed ping script (one IP argument)")
	flag.Parse()
	absolute, err := filepath.Abs(*script)
	if err != nil {
		log.Fatal(err)
	}
	if err = os.MkdirAll(*dir, 0700); err != nil {
		log.Fatal(err)
	}
	pingAbsolute, err := filepath.Abs(*ping)
	if err != nil {
		log.Fatal(err)
	}
	_, hostPrivate, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		log.Fatal(err)
	}
	clientPublic, clientPrivate, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		log.Fatal(err)
	}
	hostSigner, err := persistedHostSigner(*dir, hostPrivate)
	if err != nil {
		log.Fatal(err)
	}
	public, err := ssh.NewPublicKey(clientPublic)
	if err != nil {
		log.Fatal(err)
	}
	privateBlock, err := ssh.MarshalPrivateKey(clientPrivate, "isolated device test")
	if err != nil {
		log.Fatal(err)
	}
	key := string(pem.EncodeToMemory(privateBlock))
	tokenBytes := make([]byte, 24)
	if _, err = rand.Read(tokenBytes); err != nil {
		log.Fatal(err)
	}
	token := hex.EncodeToString(tokenBytes)
	// Reuse client credentials and the catalog token on fixture restarts.
	key, token, err = reuseClient(*dir, key, token)
	if err != nil {
		log.Fatal(err)
	}
	clientSigner, err := ssh.ParsePrivateKey([]byte(key))
	if err != nil {
		log.Fatal(err)
	}
	public = clientSigner.PublicKey()
	config := map[string]any{"tool_store_url": "http://127.0.0.1:18000", "token": token, "model_path": "/storage/emulated/0/Android/data/hn.fredi.inferencelocal/files/gemma-4-E2B-it.litertlm", "backend": "GPU", "private_key": key, "known_hosts": "[127.0.0.1]:22222 " + strings.TrimSpace(string(ssh.MarshalAuthorizedKey(hostSigner.PublicKey()))) + "\n", "hosts": []map[string]any{{"host": "127.0.0.1", "user": "agent", "port": 22222, "shell": "powershell"}}}
	encoded, _ := json.MarshalIndent(config, "", "  ")
	if err = os.WriteFile(filepath.Join(*dir, "smoke-config.json"), encoded, 0600); err != nil {
		log.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(*dir, "token"), []byte(token), 0600); err != nil {
		log.Fatal(err)
	}
	serverConfig := &ssh.ServerConfig{PublicKeyCallback: func(c ssh.ConnMetadata, k ssh.PublicKey) (*ssh.Permissions, error) {
		if c.User() == "agent" && bytes.Equal(k.Marshal(), public.Marshal()) {
			return nil, nil
		}
		return nil, fmt.Errorf("unknown test identity")
	}}
	serverConfig.AddHostKey(hostSigner)
	listener, err := net.Listen("tcp", "127.0.0.1:22222")
	if err != nil {
		log.Fatal(err)
	}
	defer listener.Close()
	log.Print("Isolated SSH status/ping fixture listening on 127.0.0.1:22222")
	for {
		raw, err := listener.Accept()
		if err != nil {
			log.Fatal(err)
		}
		go serve(raw, serverConfig, absolute, pingAbsolute)
	}
}
func encodeUTF16(s string) string {
	units := utf16.Encode([]rune(s))
	data := make([]byte, len(units)*2)
	for i, u := range units {
		data[2*i] = byte(u)
		data[2*i+1] = byte(u >> 8)
	}
	return base64.StdEncoding.EncodeToString(data)
}
func serve(raw net.Conn, config *ssh.ServerConfig, statusScript, pingScript string) {
	conn, chans, requests, err := ssh.NewServerConn(raw, config)
	if err != nil {
		raw.Close()
		return
	}
	defer conn.Close()
	go ssh.DiscardRequests(requests)
	for incoming := range chans {
		if incoming.ChannelType() != "session" {
			incoming.Reject(ssh.UnknownChannelType, "session only")
			continue
		}
		channel, reqs, err := incoming.Accept()
		if err != nil {
			continue
		}
		go func() {
			defer channel.Close()
			for req := range reqs {
				if req.Type != "exec" {
					req.Reply(false, nil)
					continue
				}
				var payload struct{ Command string }
				if ssh.Unmarshal(req.Payload, &payload) != nil {
					req.Reply(false, nil)
					return
				}
				script, args, ok := resolve(payload.Command, statusScript, pingScript)
				if !ok {
					req.Reply(false, nil)
					return
				}
				req.Reply(true, nil)
				arguments := append([]string{"-NoLogo", "-NoProfile", "-NonInteractive", "-File", script}, args...)
				cmd := exec.Command("powershell.exe", arguments...)
				cmd.Stdout = channel
				cmd.Stderr = channel.Stderr()
				code := uint32(0)
				if err := cmd.Run(); err != nil {
					code = 1
				}
				log.Printf("Executed fixed %s script; exit=%d", filepath.Base(script), code)
				channel.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{code}))
				return
			}
		}()
	}
}
