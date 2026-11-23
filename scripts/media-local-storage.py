"""Prepare only a loopback Moto store. Never uses ambient AWS credentials."""
import sys
import argparse
import json
from pathlib import Path
import boto3
from botocore.config import Config

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--export", type=Path, help="Download only the assets in this local manifests.json directory")
    args = parser.parse_args()
    s3 = boto3.client("s3", endpoint_url="http://127.0.0.1:19090", region_name="us-east-1",
                      aws_access_key_id="local-media-test", aws_secret_access_key="local-media-test",
                      config=Config(signature_version="s3v4", retries={"total_max_attempts":2},
                                    connect_timeout=3, read_timeout=10))
    if args.export:
        root = args.export.resolve()
        rows = json.loads((root / "manifests.json").read_text())
        count = 0
        for row in rows:
            prefix = row["manifest"]["basePath"].rstrip("/") + "/"
            if not prefix.startswith("media/") or ".." in prefix.split("/"):
                raise ValueError("Invalid media prefix")
            for page in s3.get_paginator("list_objects_v2").paginate(Bucket="portfolio-media-public", Prefix=prefix):
                for item in page.get("Contents", []):
                    target = (root / item["Key"]).resolve()
                    if not target.is_relative_to(root):
                        raise ValueError("Object key escapes export directory")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    s3.download_file("portfolio-media-public", item["Key"], str(target))
                    count += 1
        print(f"Downloaded {count} fresh published objects for local playback")
    else:
        for bucket in ["portfolio-media-originals", "portfolio-media-public"]:
            s3.create_bucket(Bucket=bucket)
        print("Local media buckets ready")
    return 0

if __name__ == "__main__":
    sys.exit(main())
