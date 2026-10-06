package main

import (
	"encoding/json"
	"flag"
	mock "gnss-android-tracking/test-tools/mock-receiver"
	"log"
	"net/http"
	"os"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:8080", "explicit bind address; use LAN IP for Android")
	authority := flag.String("authority", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "stable test authority UUID")
	script := flag.String("script", "", "optional local JSON fault/config script")
	flag.Parse()
	receiver, err := mock.New(*authority, nil)
	if err != nil {
		log.Fatal(err)
	}
	if *script != "" {
		b, err := os.ReadFile(*script)
		if err != nil {
			log.Fatal(err)
		}
		var input struct {
			Configs map[string]mock.Config `json:"configs"`
			Steps   []mock.Step            `json:"steps"`
		}
		if err = json.Unmarshal(b, &input); err != nil {
			log.Fatal(err)
		}
		for device, config := range input.Configs {
			if err = receiver.SetConfig(device, config); err != nil {
				log.Fatal(err)
			}
		}
		if err = receiver.Queue(input.Steps...); err != nil {
			log.Fatal(err)
		}
	}
	receiver.SetCaptureWriter(os.Stdout)
	log.Printf("TEST MEMORY ONLY: ACKs do not survive mock restart. Listening on %s", *listen)
	server := &http.Server{Addr: *listen, Handler: receiver, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 10 * time.Second, MaxHeaderBytes: 8192}
	log.Fatal(server.ListenAndServe())
}
