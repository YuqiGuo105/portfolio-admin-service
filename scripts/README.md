# scripts/

One-time data utilities for the admin content platform.

## `backfill_opensearch.py`

Reads Supabase CSV exports (`Blogs_rows.csv`, `life_blogs_rows.csv`,
`Projects_rows.csv`) and bulk-indexes them into Aiven OpenSearch as
`portfolio_content_v1`. Then atomically points the alias
`portfolio_content_current` at it.

After this runs, the Java admin service (which writes to alias
`portfolio_content_current`) keeps the index in sync going forward.

### Run

```bash
cd portfolio-admin-service
python3 -m venv .venv && source .venv/bin/activate
pip install 'opensearch-py>=2.6.0'

export OPENSEARCH_HOST='os-b4cbaea-yuqi-791c.a.aivencloud.com'
export OPENSEARCH_PORT=27099
export OPENSEARCH_USERNAME='avnadmin'
export OPENSEARCH_PASSWORD='...'      # do NOT commit

python scripts/backfill_opensearch.py
```

### Re-running

Safe. The script uses fixed document ids (`{TYPE}:{id}`) so re-running
overwrites prior documents in place. To reindex with new mappings:

```bash
export OPENSEARCH_INDEX_VERSION='portfolio_content_v2'
python scripts/backfill_opensearch.py
# alias automatically flips to v2; old v1 can be deleted later.
```
