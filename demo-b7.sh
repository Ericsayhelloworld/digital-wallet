login() { curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"correct-horse-battery\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])'; }
ALICE=$(login alice)
BOB=$(login bob)
echo "--- No token:"
curl -s localhost:8080/api/v1/wallets/me; echo
DEP=$(curl -s localhost:8080/api/v1/deposits -H "Authorization: Bearer $ALICE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["items"][0]["id"])')
echo "--- Bob requests Alice's deposit $DEP:"
curl -s localhost:8080/api/v1/deposits/$DEP -H "Authorization: Bearer $BOB"; echo
