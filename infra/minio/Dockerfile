FROM golang:1.24.8-alpine3.21 AS build
RUN apk add --no-cache git
ENV CGO_ENABLED=0
RUN GOBIN=/out go install \
    -ldflags="-s -w -X github.com/minio/minio/cmd.Version=2025-10-15T17:29:55Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-10-15T17-29-55Z -X github.com/minio/minio/cmd.CommitID=9e49d5e7a648" \
    github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z

FROM alpine:3.21
RUN apk add --no-cache ca-certificates
COPY --from=build /out/minio /usr/local/bin/minio
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
