package protocol

import "testing"

func TestEnvelopeRoundTrip(t *testing.T) {
	msg := &Envelope{
		Type:    TypeData,
		ConnID:  "abc",
		DstVIP:  "10.2.2.123",
		DstPort: 8080,
		Payload: []byte("hello"),
		Seq:     7,
	}
	b, err := Marshal(msg)
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}
	decoded, err := Unmarshal(b)
	if err != nil {
		t.Fatalf("unmarshal failed: %v", err)
	}
	if decoded.Type != msg.Type || decoded.ConnID != msg.ConnID || decoded.DstPort != msg.DstPort {
		t.Fatalf("decoded mismatch: %#v", decoded)
	}
	if string(decoded.Payload) != "hello" {
		t.Fatalf("payload mismatch: %s", string(decoded.Payload))
	}
	if decoded.Version != VersionV1 {
		t.Fatalf("version mismatch: %s", decoded.Version)
	}
}
