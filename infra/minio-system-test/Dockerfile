FROM golang:1.25.1-alpine AS build

ARG MINIO_COMMIT=9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a

ENV CGO_ENABLED=0

RUN go install "github.com/minio/minio@${MINIO_COMMIT}"

FROM alpine:3.22.1

RUN apk add --no-cache ca-certificates tzdata && \
    addgroup -S -g 10001 minio && \
    adduser -S -D -H -u 10001 -G minio minio && \
    mkdir -p /data && \
    chown -R minio:minio /data

COPY --from=build /go/bin/minio /usr/local/bin/minio

USER 10001:10001
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
CMD ["server", "/data", "--console-address", ":9001"]
