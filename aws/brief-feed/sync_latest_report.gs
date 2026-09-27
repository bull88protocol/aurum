/**
 * Apps Script: copy the newest gold_report_<date> doc into the one doc the feed reads.
 *
 * Why this exists. The Lambda reads a single, fixed Google Doc through the plain-text export URL,
 * which needs no OAuth and no client library — that is what keeps the function stdlib-only and
 * free of a fourth credential. But a scheduled Gemini action writes a NEW doc each day, and
 * *finding* a doc by name needs the Drive API, which needs exactly the auth we avoided.
 *
 * So the search happens here instead, inside Google, where Drive access is free and already
 * authenticated as you. This script finds the newest matching doc and copies its text into the
 * fixed inbox doc. Nothing changes on the AWS side and no credential leaves Google.
 *
 * SETUP
 *   1. script.google.com -> New project -> paste this in.
 *   2. Fill in the three constants below.
 *   3. Run syncLatestGoldReport once by hand. It will ask for Drive/Docs permission; grant it,
 *      then read the execution log to confirm it found the right doc.
 *   4. Triggers (clock icon) -> Add Trigger -> syncLatestGoldReport, Time-driven, Day timer,
 *      4pm-5pm. That is after the Deep Research run and before the feed's 17:17 ET pass.
 *
 * The inbox doc must be shared "Anyone with the link -> Viewer" so the Lambda can export it.
 * Verify the whole chain afterwards with: python3 aws/brief-feed/check_doc.py <INBOX_DOC_ID>
 */

// The folder the scheduled Gemini action saves into. For "My Drive" root, use DriveApp.getRootFolder().
const FOLDER_ID = 'PASTE_FOLDER_ID_HERE';

// The one fixed doc the Lambda reads. Same id as DEEP_RESEARCH_DOC_ID on the function.
const INBOX_DOC_ID = 'PASTE_INBOX_DOC_ID_HERE';

// Matches gold_report_09272026, gold_report_2026-09-27, etc.
const NAME_PREFIX = 'gold_report_';


function syncLatestGoldReport() {
  const folder = FOLDER_ID === 'PASTE_FOLDER_ID_HERE'
      ? DriveApp.getRootFolder()
      : DriveApp.getFolderById(FOLDER_ID);

  // Newest by creation time rather than by the date in the name: names are free text and a
  // mistyped one would otherwise win or lose silently, where createdDate cannot be wrong.
  let newest = null;
  const files = folder.getFilesByType(MimeType.GOOGLE_DOCS);
  while (files.hasNext()) {
    const f = files.next();
    if (f.getName().indexOf(NAME_PREFIX) !== 0) continue;
    if (!newest || f.getDateCreated() > newest.getDateCreated()) newest = f;
  }

  if (!newest) {
    console.log('No doc starting with "' + NAME_PREFIX + '" in that folder. Nothing copied.');
    return;
  }

  const text = DocumentApp.openById(newest.getId()).getBody().getText();

  // Do not overwrite a good inbox with a bad report. If the newest doc has no JSON block, the
  // Deep Research run failed or is still writing, and the feed is better off keeping yesterday's
  // report — which its own session check will then reject, falling back to the RSS brief.
  if (text.indexOf('"schema"') === -1) {
    console.log('Newest doc (' + newest.getName() + ') has no JSON block. Inbox left unchanged.');
    return;
  }

  const inbox = DocumentApp.openById(INBOX_DOC_ID);
  inbox.getBody().setText(text);
  inbox.saveAndClose();

  console.log('Copied ' + newest.getName() + ' (' + newest.getDateCreated() +
              ', ' + text.length + ' chars) into the inbox doc.');
}
