//go:build windows

package shared

import "golang.org/x/sys/windows"

func HasAdminPrivileges() (bool, error) {
	token := windows.Token(0)
	if token.IsElevated() {
		return true, nil
	}

	// Some Windows environments can report a false negative for IsElevated()
	// while the process is running under an Administrator context.
	if member, memberErr := isUserAnAdmin(); memberErr == nil && member {
		return true, nil
	}

	return false, nil
}

func isUserAnAdmin() (bool, error) {
	shell32 := windows.NewLazySystemDLL("shell32.dll")
	proc := shell32.NewProc("IsUserAnAdmin")

	if err := shell32.Load(); err != nil {
		return false, err
	}
	if err := proc.Find(); err != nil {
		return false, err
	}

	r1, _, callErr := proc.Call()
	if r1 == 0 {
		if callErr != windows.ERROR_SUCCESS && callErr != windows.Errno(0) {
			return false, callErr
		}
		return false, nil
	}
	return true, nil
}
