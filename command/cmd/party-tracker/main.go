package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"time"

	"github.com/suraj-singh12/gnss-android-tracking/command/internal/core"
	"github.com/suraj-singh12/gnss-android-tracking/command/web"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
func run() error {
	dir, err := os.UserConfigDir()
	if err != nil {
		return err
	}
	database := flag.String("db", filepath.Join(dir, "party-tracker", "command.sqlite"), "local SQLite file (back up while Command is stopped)")
	ingest := flag.String("ingest-listen", ":8080", "LAN phone ingestion address")
	local := flag.String("dashboard-listen", "127.0.0.1:8081", "local dashboard/control address")
	flag.Parse()
	store, err := core.Open(*database)
	if err != nil {
		return err
	}
	defer store.Close()
	phone, err := net.Listen("tcp", *ingest)
	if err != nil {
		return err
	}
	defer phone.Close()
	ui, err := net.Listen("tcp", *local)
	if err != nil {
		return err
	}
	defer ui.Close()
	servers := []*http.Server{{Handler: store.IngestHandler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second}, {Handler: store.LocalHandler(web.Handler()), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second}}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	fail := make(chan error, 2)
	go func() { fail <- servers[0].Serve(phone) }()
	go func() { fail <- servers[1].Serve(ui) }()
	log.Printf("Command dashboard http://%s; phone ingestion %s; SQLite %s", ui.Addr(), phone.Addr(), *database)
	select {
	case <-ctx.Done():
	case err = <-fail:
		if err != nil && err != http.ErrServerClosed {
			return fmt.Errorf("server: %w", err)
		}
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for _, server := range servers {
		if err = server.Shutdown(shutdown); err != nil {
			return err
		}
	}
	return nil
}
