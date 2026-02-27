FROM golang:1.24-alpine AS builder

WORKDIR /src
COPY go/go.mod go/go.sum ./
RUN go mod download

COPY go/ ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/htunnel-server ./cmd/htunnel-server

FROM alpine:3.21

RUN adduser -D -u 10001 app
WORKDIR /app

COPY --from=builder /out/htunnel-server /app/htunnel-server
COPY go/configs/server.yaml /app/configs/server.yaml

EXPOSE 8082
USER app

ENTRYPOINT ["/app/htunnel-server"]
CMD ["-config", "/app/configs/server.yaml"]
