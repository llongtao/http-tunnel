package shared

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"time"
)

func GenerateHS256JWT(secret string, claims map[string]interface{}) (string, error) {
	header := map[string]interface{}{
		"alg": "HS256",
		"typ": "JWT",
	}
	headerJSON, err := json.Marshal(header)
	if err != nil {
		return "", fmt.Errorf("marshal header failed: %w", err)
	}
	claimsJSON, err := json.Marshal(claims)
	if err != nil {
		return "", fmt.Errorf("marshal claims failed: %w", err)
	}
	headerEnc := base64.RawURLEncoding.EncodeToString(headerJSON)
	claimsEnc := base64.RawURLEncoding.EncodeToString(claimsJSON)
	unsigned := headerEnc + "." + claimsEnc
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(unsigned))
	sig := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return unsigned + "." + sig, nil
}

func BuildAgentJWTClaims(issuer, audience, subject string, expireHours int) map[string]interface{} {
	if expireHours <= 0 {
		expireHours = 24
	}
	now := time.Now()
	claims := map[string]interface{}{
		"iss": issuer,
		"aud": audience,
		"sub": subject,
		"iat": now.Unix(),
		"nbf": now.Unix(),
		"exp": now.Add(time.Duration(expireHours) * time.Hour).Unix(),
	}
	return claims
}
