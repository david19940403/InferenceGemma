package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"golang.org/x/crypto/ssh"
	"net"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf16"
)

const commandPrefix = "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand "
const commandSuffix = "; if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }"

func command(script string, args ...string) string {
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
	text := "& " + quote(script)
	for _, arg := range args {
		text += " " + quote(arg)
	}
	return commandPrefix + encodeUTF16(text+commandSuffix)
}

// Resolve fixed executable paths and data arguments; never run incoming shell text.
func resolve(wire, statusScript, pingScript string) (string, []string, bool) {
	if wire == command(statusScript) {
		return statusScript, nil, true
	}
	daily := filepath.Join(filepath.Dir(statusScript), "daily-tools.ps1")
	approved := map[string][]string{
		"vpn": {""}, "adb": {""}, "servicios": {""},
		"git":      {"inferencegemma", "scanflex", "posandroid", "prestashop"},
		"apks":     {"inferencegemma", "scanflex", "posandroid"},
		"imagenes": {"productos"}, "n8n": {"aperturas_takepos"},
	}
	for action, subjects := range approved {
		for _, subject := range subjects {
			args := []string{action}
			if subject != "" {
				args = append(args, subject)
			}
			if wire == command(daily, args...) {
				return daily, args, true
			}
		}
	}
	if !strings.HasPrefix(wire, commandPrefix) {
		return "", nil, false
	}
	data, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(wire, commandPrefix))
	if err != nil || len(data)%2 != 0 {
		return "", nil, false
	}
	units := make([]uint16, len(data)/2)
	for i := range units {
		units[i] = uint16(data[2*i]) | uint16(data[2*i+1])<<8
	}
	text := string(utf16.Decode(units))
	prefix := "& '" + strings.ReplaceAll(pingScript, "'", "''") + "' '"
	suffix := "'" + commandSuffix
	if !strings.HasPrefix(text, prefix) || !strings.HasSuffix(text, suffix) {
		return "", nil, false
	}
	ip := strings.TrimSuffix(strings.TrimPrefix(text, prefix), suffix)
	if net.ParseIP(ip) == nil || wire != command(pingScript, ip) {
		return "", nil, false
	}
	return pingScript, []string{"-Destination", ip}, true
}

func persistedHostSigner(dir string, private ed25519.PrivateKey) (ssh.Signer, error) {
	path := filepath.Join(dir, "ssh-host-key.pem")
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		block, e := ssh.MarshalPrivateKey(private, "isolated device test")
		if e != nil {
			return nil, e
		}
		data = pem.EncodeToMemory(block)
		err = os.WriteFile(path, data, 0600)
	}
	if err != nil {
		return nil, err
	}
	return ssh.ParsePrivateKey(data)
}

func reuseClient(dir, key, token string) (string, string, error) {
	data, err := os.ReadFile(filepath.Join(dir, "smoke-config.json"))
	if os.IsNotExist(err) {
		return key, token, nil
	}
	if err != nil {
		return "", "", err
	}
	var config struct {
		PrivateKey string `json:"private_key"`
		Token      string `json:"token"`
	}
	if err = json.Unmarshal(data, &config); err != nil {
		return "", "", err
	}
	if config.PrivateKey != "" {
		key = config.PrivateKey
	}
	if config.Token != "" {
		token = config.Token
	}
	return key, token, nil
}
