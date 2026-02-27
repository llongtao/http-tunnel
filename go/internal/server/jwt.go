package server

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

type jwtHeader struct {
	Alg string `json:"alg"`
	Typ string `json:"typ"`
}

type jwtClaims struct {
	Iss string      `json:"iss"`
	Aud interface{} `json:"aud"`
	Exp int64       `json:"exp"`
	Nbf int64       `json:"nbf"`
	Iat int64       `json:"iat"`
}

func validateHS256Token(token, secret, issuer, audience string, skew time.Duration) error {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return errors.New("invalid jwt format")
	}

	headerBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return fmt.Errorf("decode jwt header failed: %w", err)
	}
	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return fmt.Errorf("decode jwt payload failed: %w", err)
	}
	signatureBytes, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return fmt.Errorf("decode jwt signature failed: %w", err)
	}

	var header jwtHeader
	if err := json.Unmarshal(headerBytes, &header); err != nil {
		return fmt.Errorf("parse jwt header failed: %w", err)
	}
	if header.Alg != "HS256" {
		return fmt.Errorf("unsupported jwt alg: %s", header.Alg)
	}

	unsigned := parts[0] + "." + parts[1]
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(unsigned))
	expectedSig := mac.Sum(nil)
	if !hmac.Equal(expectedSig, signatureBytes) {
		return errors.New("jwt signature mismatch")
	}

	var claims jwtClaims
	if err := json.Unmarshal(payloadBytes, &claims); err != nil {
		return fmt.Errorf("parse jwt claims failed: %w", err)
	}

	now := time.Now().Unix()
	leeway := int64(skew.Seconds())
	if issuer != "" && claims.Iss != issuer {
		return errors.New("issuer mismatch")
	}
	if audience != "" && !matchAudience(claims.Aud, audience) {
		return errors.New("audience mismatch")
	}
	if claims.Exp != 0 && now > claims.Exp+leeway {
		return errors.New("token expired")
	}
	if claims.Nbf != 0 && now+leeway < claims.Nbf {
		return errors.New("token not active")
	}
	return nil
}

func matchAudience(aud interface{}, expected string) bool {
	switch v := aud.(type) {
	case string:
		return v == expected
	case []interface{}:
		for _, item := range v {
			if s, ok := item.(string); ok && s == expected {
				return true
			}
		}
	}
	return false
}
