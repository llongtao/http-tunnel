//go:build windows

package shared

import "golang.org/x/sys/windows"

func HasAdminPrivileges() (bool, error) {
	token := windows.Token(0)
	return token.IsElevated(), nil
}
