import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";

export const BUCKET = process.env.S3_BUCKET ?? "fadstream-recordings";

export const s3 = new S3Client({
  region: process.env.S3_REGION ?? "us-east-1",
  endpoint: process.env.S3_ENDPOINT ?? "http://minio:9000",
  forcePathStyle: true,
  credentials: {
    accessKeyId: process.env.S3_ACCESS_KEY ?? "fadstream",
    secretAccessKey: process.env.S3_SECRET_KEY ?? "change-me-in-prod",
  },
});

export async function putObject(key: string, body: Buffer, contentType: string) {
  await s3.send(new PutObjectCommand({ Bucket: BUCKET, Key: key, Body: body, ContentType: contentType }));
  return `s3://${BUCKET}/${key}`;
}
