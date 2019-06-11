#!/usr/bin/env bash

set -e

if [ -z "$AWS_PROFILE" ]; then
    echo "Please set your AWS_PROFILE to the desired account"
    exit 1
fi

AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query "Account" --output text)
DOCKER_REGISTRY=${AWS_ACCOUNT_ID}.dkr.ecr.eu-central-1.amazonaws.com
S3_BUCKET_WEB_ASSETS="${AWS_PROFILE}-web-assets"

web_assets_dir="./webApp/target/scala-2.12/scalajs-bundler/main/dist"

# use git branch as version for woost
git_branch=$(git symbolic-ref --short HEAD --quiet || git rev-parse HEAD) # branch, or fall back to commit hash
case "$git_branch" in
    master )
        version="latest"
        ;;
    * )
        version="$git_branch"
esac

# Copy web assets to set bucket web assets under folder version, so cloudfront can serve them when switch to that version
# TODO: default caching how long? 24h for now
aws s3 sync "$web_assets_dir" "s3://$S3_BUCKET_WEB_ASSETS/$version/" --metadata-directive REPLACE --cache-control max-age=86400
# set caching headers on some objects default caching is set in cloudformation deployment (or default 24h)
# TODO: better solution? unique names? just lookup root object in cloudfront?
function short_caching() {
    local name="$1"
    local s3_path="s3://$S3_BUCKET_WEB_ASSETS/$version/$name"
    # cache for a few minutes
    aws s3 cp $s3_path $s3_path --metadata-directive REPLACE --cache-control max-age=300
}
short_caching "index.html"
short_caching "sw.js"
short_caching "site.webmanifest"

# publish docker images to our registry
eval $(aws ecr get-login --no-include-email)
function docker_push() {
    local image="$1"
    local version="$2"
    local source_image="$image:$version"
    local target_image="$DOCKER_REGISTRY/$image:$version"

    docker tag $source_image $target_image
    docker push $target_image
}
docker_push "woost/db-migration" "$version-core"
docker_push "woost/core" "$version"
