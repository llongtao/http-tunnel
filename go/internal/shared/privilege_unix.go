//go:build !windows

package shared

import "os"

func HasAdminPrivileges() (bool, error) {
	return os.Geteuid() == 0, nil
}
