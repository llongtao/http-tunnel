package protocol

import (
	"encoding/binary"
	"errors"
	"fmt"
)

const (
	VersionV1 = "v1"

	TypeAuth     = "auth"
	TypeAuthResp = "auth_resp"
	TypeOpen     = "open"
	TypeOpenResp = "open_resp"
	TypeData     = "data"
	TypeClose    = "close"
	TypePing     = "ping"
	TypePong     = "pong"
	TypeError    = "error"
)

// Envelope follows tunnel.proto and is encoded as protobuf wire format.
type Envelope struct {
	Version      string `json:"version,omitempty"`
	Type         string `json:"type,omitempty"`
	RequestID    string `json:"request_id,omitempty"`
	ConnID       string `json:"conn_id,omitempty"`
	Token        string `json:"token,omitempty"`
	AgentID      string `json:"agent_id,omitempty"`
	AgentVersion string `json:"agent_version,omitempty"`
	DstVIP       string `json:"dst_vip,omitempty"`
	DstPort      uint32 `json:"dst_port,omitempty"`
	SrcAddr      string `json:"src_addr,omitempty"`
	Payload      []byte `json:"payload,omitempty"`
	Reason       string `json:"reason,omitempty"`
	Ts           int64  `json:"ts,omitempty"`
	Seq          uint32 `json:"seq,omitempty"`
	Ok           bool   `json:"ok,omitempty"`
}

func Marshal(msg *Envelope) ([]byte, error) {
	if msg == nil {
		return nil, errors.New("nil envelope")
	}
	if msg.Version == "" {
		msg.Version = VersionV1
	}

	out := make([]byte, 0, 256)
	out = appendString(out, 1, msg.Version)
	out = appendString(out, 2, msg.Type)
	out = appendString(out, 3, msg.RequestID)
	out = appendString(out, 4, msg.ConnID)
	out = appendString(out, 5, msg.Token)
	out = appendString(out, 6, msg.AgentID)
	out = appendString(out, 7, msg.AgentVersion)
	out = appendString(out, 8, msg.DstVIP)
	out = appendU32(out, 9, msg.DstPort)
	out = appendString(out, 10, msg.SrcAddr)
	out = appendBytes(out, 11, msg.Payload)
	out = appendString(out, 12, msg.Reason)
	out = appendI64(out, 13, msg.Ts)
	out = appendU32(out, 14, msg.Seq)
	out = appendBool(out, 15, msg.Ok)
	return out, nil
}

func Unmarshal(data []byte) (*Envelope, error) {
	if len(data) == 0 {
		return nil, errors.New("empty payload")
	}
	msg := &Envelope{}
	for i := 0; i < len(data); {
		key, n := binary.Uvarint(data[i:])
		if n <= 0 {
			return nil, fmt.Errorf("invalid key varint at %d", i)
		}
		i += n
		fieldNum := int(key >> 3)
		wireType := int(key & 0x7)

		switch wireType {
		case 0:
			v, n := binary.Uvarint(data[i:])
			if n <= 0 {
				return nil, fmt.Errorf("invalid varint field=%d", fieldNum)
			}
			i += n
			switch fieldNum {
			case 9:
				msg.DstPort = uint32(v)
			case 13:
				msg.Ts = int64(v)
			case 14:
				msg.Seq = uint32(v)
			case 15:
				msg.Ok = v != 0
			}
		case 2:
			l, n := binary.Uvarint(data[i:])
			if n <= 0 {
				return nil, fmt.Errorf("invalid length field=%d", fieldNum)
			}
			i += n
			if i+int(l) > len(data) {
				return nil, fmt.Errorf("truncated field=%d", fieldNum)
			}
			chunk := data[i : i+int(l)]
			i += int(l)
			switch fieldNum {
			case 1:
				msg.Version = string(chunk)
			case 2:
				msg.Type = string(chunk)
			case 3:
				msg.RequestID = string(chunk)
			case 4:
				msg.ConnID = string(chunk)
			case 5:
				msg.Token = string(chunk)
			case 6:
				msg.AgentID = string(chunk)
			case 7:
				msg.AgentVersion = string(chunk)
			case 8:
				msg.DstVIP = string(chunk)
			case 10:
				msg.SrcAddr = string(chunk)
			case 11:
				msg.Payload = append([]byte(nil), chunk...)
			case 12:
				msg.Reason = string(chunk)
			}
		default:
			return nil, fmt.Errorf("unsupported wire type=%d", wireType)
		}
	}
	if msg.Version == "" {
		msg.Version = VersionV1
	}
	return msg, nil
}

func appendKey(out []byte, fieldNum int, wireType int) []byte {
	key := uint64((fieldNum << 3) | wireType)
	return appendVarint(out, key)
}

func appendVarint(out []byte, v uint64) []byte {
	buf := make([]byte, binary.MaxVarintLen64)
	n := binary.PutUvarint(buf, v)
	return append(out, buf[:n]...)
}

func appendString(out []byte, fieldNum int, value string) []byte {
	if value == "" {
		return out
	}
	out = appendKey(out, fieldNum, 2)
	out = appendVarint(out, uint64(len(value)))
	return append(out, value...)
}

func appendBytes(out []byte, fieldNum int, value []byte) []byte {
	if len(value) == 0 {
		return out
	}
	out = appendKey(out, fieldNum, 2)
	out = appendVarint(out, uint64(len(value)))
	return append(out, value...)
}

func appendU32(out []byte, fieldNum int, value uint32) []byte {
	if value == 0 {
		return out
	}
	out = appendKey(out, fieldNum, 0)
	return appendVarint(out, uint64(value))
}

func appendI64(out []byte, fieldNum int, value int64) []byte {
	if value == 0 {
		return out
	}
	out = appendKey(out, fieldNum, 0)
	return appendVarint(out, uint64(value))
}

func appendBool(out []byte, fieldNum int, value bool) []byte {
	if !value {
		return out
	}
	out = appendKey(out, fieldNum, 0)
	return appendVarint(out, 1)
}
