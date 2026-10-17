#!/usr/bin/env bash
# Generates SYNTHETIC chest imaging and uploads it to RDSS for a demo patient through the
# normal authorized API, then adds DEMONSTRATION findings. Requires docker, curl and python3.
#   ./tools/load-demo-imaging.sh [base_url] [username] [mrn]
set -euo pipefail
BASE_URL="${1:-http://localhost:8080}"
USERNAME="${2:-dr.hale}"
MRN="${3:-MRN100002}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/tools/synthetic-dicom/out"
PASSWORD="${DEMO_USER_PASSWORD:-$(grep -E '^DEMO_USER_PASSWORD=' "$ROOT/.env" 2>/dev/null | cut -d= -f2- || true)}"
[ -n "$PASSWORD" ] || { echo "Set DEMO_USER_PASSWORD (environment or .env)"; exit 1; }
json() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1"; }

echo "1/4 Generating synthetic DICOM (Docker)..."
docker build -q -t rdss-synthetic-dicom "$ROOT/tools/synthetic-dicom" >/dev/null
mkdir -p "$OUT"
HOST_OUT="$OUT"; command -v cygpath >/dev/null && HOST_OUT="$(cygpath -w "$OUT")"
MSYS_NO_PATHCONV=1 docker run --rm --user 0 -v "$HOST_OUT:/out" rdss-synthetic-dicom --out /out --patient-id "$MRN"

echo "2/4 Signing in as $USERNAME..."
TOKEN=$(curl -sf -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" | json "d['accessToken']")
PID=$(curl -sf "$BASE_URL/api/v1/patients?q=$MRN" -H "Authorization: Bearer $TOKEN" \
  | json "[p['id'] for p in d['content'] if p['mrn']=='$MRN'][0]")

echo "3/4 Uploading studies for patient $PID ($MRN)..."
declare -A STUDY
for dir in ct-prior ct-current dx-chest; do
  args=()
  for f in "$OUT/$dir"/*.dcm; do args+=(-F "files=@$f;type=application/dicom"); done
  resp=$(curl -sf -H "Authorization: Bearer $TOKEN" "${args[@]}" "$BASE_URL/api/v1/patients/$PID/imaging-studies/upload")
  STUDY[$dir]=$(echo "$resp" | json "d['studies'][0]['id']")
  echo "   $dir: study ${STUDY[$dir]}, $(echo "$resp" | json "d['instancesStored']") new instance(s)"
done

echo "4/4 Adding DEMONSTRATION findings to the current CT..."
for body in \
  '{"type":"PULMONARY_NODULE","laterality":"RIGHT","sizeMm":9,"location":"Right upper lobe","source":"DEMONSTRATION","status":"FINAL","description":"Synthetic 9 mm solid nodule in the phantom (demonstration data, not a real finding)."}' \
  '{"type":"PLEURAL_EFFUSION","laterality":"LEFT","location":"Left base, dependent","source":"DEMONSTRATION","status":"FINAL","description":"Synthetic small dependent left effusion in the phantom (demonstration data)."}'; do
  curl -sf -o /dev/null -X POST "$BASE_URL/api/v1/imaging-studies/${STUDY[ct-current]}/findings" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body"
done
echo "Done. Open $BASE_URL/patients/$PID?tab=imaging"
