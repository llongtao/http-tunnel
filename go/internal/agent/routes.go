package agent

import (
	"fmt"
	"net/netip"
	"runtime"
	"strconv"
	"strings"
)

func (c *Config) EnsureRouteCommands(goos string) error {
	cidrs, err := NormalizeRouteCIDRs(c.Tun.RouteCIDRs)
	if err != nil {
		return err
	}
	c.Tun.RouteCIDRs = cidrs
	if len(cidrs) == 0 {
		return nil
	}
	setup, cleanup, err := BuildRouteCommands(goos, cidrs, c.Tun.Addr, c.Tun.Gateway)
	if err != nil {
		return err
	}
	regenerateLegacy := strings.EqualFold(goos, "darwin") && hasLegacyDarwinIfaceRegex(c.Tun.AutoRouteCommands)
	if len(c.Tun.AutoRouteCommands) == 0 || regenerateLegacy {
		c.Tun.AutoRouteCommands = setup
	}
	if len(c.Tun.AutoRouteCleanupCommands) == 0 || regenerateLegacy {
		c.Tun.AutoRouteCleanupCommands = cleanup
	}
	return nil
}

func hasLegacyDarwinIfaceRegex(commands []string) bool {
	for _, cmd := range commands {
		if strings.Contains(cmd, `ifconfig | awk '/^[a-z0-9]+:/{gsub(":","",$1);i=$1} /inet `) {
			return true
		}
	}
	return false
}

func NormalizeRouteCIDRs(routeCIDRs []string) ([]string, error) {
	out := make([]string, 0, len(routeCIDRs))
	seen := map[string]struct{}{}
	for _, item := range routeCIDRs {
		cidr := strings.TrimSpace(item)
		if cidr == "" {
			continue
		}
		prefix, err := netip.ParsePrefix(cidr)
		if err != nil {
			return nil, fmt.Errorf("invalid route cidr %q: %w", cidr, err)
		}
		prefix = prefix.Masked()
		normalized := prefix.String()
		if _, ok := seen[normalized]; ok {
			continue
		}
		seen[normalized] = struct{}{}
		out = append(out, normalized)
	}
	return out, nil
}

func BuildRouteCommands(goos string, routeCIDRs []string, tunAddr string, tunGateway string) ([]string, []string, error) {
	cidrs, err := NormalizeRouteCIDRs(routeCIDRs)
	if err != nil {
		return nil, nil, err
	}
	if len(cidrs) == 0 {
		return nil, nil, nil
	}
	if goos == "" {
		goos = runtime.GOOS
	}
	switch strings.ToLower(goos) {
	case "darwin":
		return buildDarwinRouteCommands(cidrs, tunAddr, tunGateway)
	case "windows":
		return buildWindowsRouteCommands(cidrs, tunGateway)
	default:
		return nil, nil, fmt.Errorf("auto route generation does not support os=%s", goos)
	}
}

func buildDarwinRouteCommands(routeCIDRs []string, tunAddr string, tunGateway string) ([]string, []string, error) {
	if strings.TrimSpace(tunAddr) == "" || strings.TrimSpace(tunGateway) == "" {
		return nil, nil, fmt.Errorf("tun.addr and tun.gateway are required for darwin route generation")
	}
	findIface := fmt.Sprintf(`for i in $(seq 1 30); do IFACE=$(ifconfig | awk '$1 ~ /^[a-z0-9]+:$/ {gsub(":","",$1);i=$1} $1=="inet" && $2=="%s" && $3=="-->" && $4=="%s" {print i; exit}'); [ -n "$IFACE" ] && break; sleep 1; done; [ -n "$IFACE" ] || exit 1`, strings.TrimSpace(tunAddr), strings.TrimSpace(tunGateway))

	setup := make([]string, 0, len(routeCIDRs))
	cleanup := make([]string, 0, len(routeCIDRs))
	for _, cidr := range routeCIDRs {
		prefix, _ := netip.ParsePrefix(cidr)
		prefix = prefix.Masked()
		bits := prefix.Bits()
		if bits == 32 {
			host := prefix.Addr().String()
			setup = append(setup,
				fmt.Sprintf(`%s; route -n delete -host %s >/dev/null 2>&1 || true; route -n add -host %s -interface "$IFACE" || route -n change -host %s -interface "$IFACE"`, findIface, host, host, host),
			)
			cleanup = append(cleanup, fmt.Sprintf(`route -n delete -host %s >/dev/null 2>&1 || true`, host))
			continue
		}

		setup = append(setup,
			fmt.Sprintf(`%s; route -n delete -net %s >/dev/null 2>&1 || true; route -n add -net %s -interface "$IFACE" || route -n change -net %s -interface "$IFACE"`, findIface, cidr, cidr, cidr),
		)
		cleanup = append(cleanup, fmt.Sprintf(`route -n delete -net %s >/dev/null 2>&1 || true`, cidr))
	}
	return setup, cleanup, nil
}

func buildWindowsRouteCommands(routeCIDRs []string, tunGateway string) ([]string, []string, error) {
	if strings.TrimSpace(tunGateway) == "" {
		return nil, nil, fmt.Errorf("tun.gateway is required for windows route generation")
	}
	setup := make([]string, 0, len(routeCIDRs))
	cleanup := make([]string, 0, len(routeCIDRs))
	for _, cidr := range routeCIDRs {
		prefix, _ := netip.ParsePrefix(cidr)
		prefix = prefix.Masked()
		bits := prefix.Bits()
		if bits == 32 {
			host := prefix.Addr().String()
			setup = append(setup, fmt.Sprintf(`route delete %s >NUL 2>NUL & route add %s mask 255.255.255.255 %s`, host, host, tunGateway))
			cleanup = append(cleanup, fmt.Sprintf(`route delete %s >NUL 2>NUL`, host))
			continue
		}
		network := prefix.Addr().String()
		mask := ipv4MaskFromPrefix(bits)
		setup = append(setup, fmt.Sprintf(`route delete %s >NUL 2>NUL & route add %s mask %s %s`, network, network, mask, tunGateway))
		cleanup = append(cleanup, fmt.Sprintf(`route delete %s >NUL 2>NUL`, network))
	}
	return setup, cleanup, nil
}

func ipv4MaskFromPrefix(bits int) string {
	if bits <= 0 {
		return "0.0.0.0"
	}
	if bits >= 32 {
		return "255.255.255.255"
	}
	mask := uint32(0xffffffff) << (32 - bits)
	o1 := byte(mask >> 24)
	o2 := byte(mask >> 16)
	o3 := byte(mask >> 8)
	o4 := byte(mask)
	return strings.Join([]string{strconv.Itoa(int(o1)), strconv.Itoa(int(o2)), strconv.Itoa(int(o3)), strconv.Itoa(int(o4))}, ".")
}
