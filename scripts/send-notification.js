// Sends one test push through FCM, to a single device or to a topic.
//
//   export GOOGLE_APPLICATION_CREDENTIALS="/path/to/serviceAccountKey.json"
//   node scripts/send-notification.js <deviceToken> [title] [body] [url]
//   node scripts/send-notification.js --topic events [title] [body] [url]
//
// The device token is the one MainActivity logs under the "Z12Push" tag. `events` is the
// topic every install subscribes to, and the one the Cloud Functions broadcast on.

const admin = require('firebase-admin');

admin.initializeApp({ credential: admin.credential.applicationDefault() });

async function sendPush(target, title, body, url) {
  const message = {
    ...target,
    notification: { title, body },
    // MainActivity reads `url` off the notification-tap intent and navigates the WebView.
    data: { url: url || '' },
    android: { priority: 'high', notification: { channelId: 'z12_default' } },
    apns: { payload: { aps: { sound: 'default' } } },
  };
  const response = await admin.messaging().send(message);
  console.log('Sent notification:', response);
}

const args = process.argv.slice(2);
let target;

if (args[0] === '--topic') {
  args.shift();
  const topic = args.shift();
  if (!topic) {
    console.error('--topic needs a topic name, e.g. --topic events');
    process.exit(1);
  }
  target = { topic };
} else {
  const token = args.shift();
  if (!token) {
    console.error('Usage: node scripts/send-notification.js <deviceToken|--topic NAME> [title] [body] [url]');
    process.exit(1);
  }
  target = { token };
}

const [title, body, url] = args;

sendPush(target, title || 'Z12 Challenge', body || 'Test push', url).catch((e) => {
  console.error(e);
  process.exit(1);
});
