import imaplib
import sys

email = "Seanoc5@gmail.com"
# todo get password from env
password = 'replace me'

print(f"Email: {email}")
print(f"Password length: {len(password)}")
print(f"Password (first 4 chars): {password[:4]}...")

try:
    print("Connecting to imap.gmail.com:993...")
    mail = imaplib.IMAP4_SSL('imap.gmail.com', 993)
    print("Connected. Attempting login...")
    mail.login(email, password)
    print("SUCCESS! Logged in.")

    # List folders
    status, folders = mail.list()
    print(f"Found {len(folders)} folders")
    for folder in folders[:5]:
        print(f"  {folder.decode()}")

    mail.logout()
except Exception as e:
    print(f"FAILED: {e}")
    sys.exit(1)

