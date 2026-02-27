package main

import (
	"flag"
	"fmt"
	"log"

	"htunnel/go/internal/shared"
)

func main() {
	secret := flag.String("secret", "change-me", "jwt hmac secret")
	issuer := flag.String("issuer", "htunnel", "jwt issuer")
	audience := flag.String("audience", "htunnel-agent", "jwt audience")
	agentID := flag.String("agent", "agent-01", "agent id")
	expire := flag.Int("expire-hour", 24, "token expiration in hours")
	flag.Parse()

	claims := shared.BuildAgentJWTClaims(*issuer, *audience, *agentID, *expire)
	token, err := shared.GenerateHS256JWT(*secret, claims)
	if err != nil {
		log.Fatalf("generate token failed: %v", err)
	}
	fmt.Println(token)
}
