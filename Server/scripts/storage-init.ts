/** Creates the private development bucket and expires staging objects after one day. */
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
