/**
 * 初始化配置指定的私有 bucket，并设置临时对象的一天过期策略。
 * 会修改对象存储配置，不创建帖子或消息。
 */
import {
  CreateBucketCommand,
  HeadBucketCommand,
  PutBucketLifecycleConfigurationCommand,
} from "@aws-sdk/client-s3";
import { config } from "../src/config.js";
import { Storage } from "../src/modules/images/images.storage.js";
const storage = new Storage(config());
try {
  await storage.client.send(new HeadBucketCommand({ Bucket: storage.bucket }));
} catch (e) {
  if (
    (e as { $metadata?: { httpStatusCode?: number } }).$metadata
      ?.httpStatusCode !== 404
  )
    throw e;
  await storage.client.send(
    new CreateBucketCommand({ Bucket: storage.bucket }),
  );
}
await storage.client.send(
  new PutBucketLifecycleConfigurationCommand({
    Bucket: storage.bucket,
    LifecycleConfiguration: {
      Rules: [
        {
          ID: "expire-staging",
          Status: "Enabled",
          Filter: { Prefix: "uploads/" },
          Expiration: { Days: 1 },
        },
      ],
    },
  }),
);
console.log("Private bucket initialized");
