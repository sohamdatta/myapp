// A throwaway mail server for the browser tests: takes anything on SMTP, keeps it in memory, and lists it over HTTP.
//   node mail-sink.mjs            SMTP on 1025, HTTP on 8025 (APP_SMTP_HOST=localhost APP_SMTP_PORT=1025 APP_SMTP_SECURITY=none)
//   GET  /messages?to=a@b.test    [{ to, subject, text, at }] newest last
//   DELETE /messages              forget everything
import http from 'node:http';
import { SMTPServer } from 'smtp-server';
import { simpleParser } from 'mailparser';

const messages = [];
const smtpPort = Number(process.env.MAIL_SINK_SMTP_PORT || 1025);
const httpPort = Number(process.env.MAIL_SINK_HTTP_PORT || 8025);

const smtp = new SMTPServer({
  authOptional: true,
  disabledCommands: ['STARTTLS'],
  onData(stream, session, callback) {
    simpleParser(stream)
      .then((mail) => {
        for (const rcpt of session.envelope.rcptTo) {
          messages.push({ to: rcpt.address.toLowerCase(), subject: mail.subject || '', text: mail.text || '', at: Date.now() });
        }
        callback();
      })
      .catch(callback);
  },
});
smtp.listen(smtpPort, '127.0.0.1');

http
  .createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname !== '/messages') {
      res.writeHead(404).end();
      return;
    }
    if (req.method === 'DELETE') {
      messages.length = 0;
      res.writeHead(204).end();
      return;
    }
    const to = (url.searchParams.get('to') || '').toLowerCase();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(messages.filter((m) => !to || m.to === to)));
  })
  .listen(httpPort, '127.0.0.1', () => console.log(`mail sink: smtp ${smtpPort}, http ${httpPort}`));
