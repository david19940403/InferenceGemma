//go:build !windows

package main

import "fmt"

func isWindowsService(_ []string) bool { return false }

func runWindowsService() error {
	return fmt.Errorf("Windows service mode is only available on Windows")
}
