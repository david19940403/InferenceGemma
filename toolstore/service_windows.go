//go:build windows

package main

import (
	"context"

	"golang.org/x/sys/windows/svc"
)

const windowsServiceName = "InferenceGemmaToolStore"

func isWindowsService(args []string) bool {
	for _, arg := range args {
		if arg == "--service" {
			return true
		}
	}
	return false
}

func runWindowsService() error {
	return svc.Run(windowsServiceName, &toolStoreService{})
}

type toolStoreService struct{}

func (s *toolStoreService) Execute(_ []string, requests <-chan svc.ChangeRequest, changes chan<- svc.Status) (bool, uint32) {
	changes <- svc.Status{State: svc.StartPending, WaitHint: 5000}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	finished := make(chan error, 1)
	go func() { finished <- runServer(ctx) }()
	changes <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for {
		select {
		case request := <-requests:
			switch request.Cmd {
			case svc.Interrogate:
				changes <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
			case svc.Stop, svc.Shutdown:
				changes <- svc.Status{State: svc.StopPending, WaitHint: 5000}
				cancel()
				<-finished
				return false, 0
			}
		case <-finished:
			return false, 1
		}
	}
}
