`# Getting Harbor Family onto Google Play
`
`What is done in the code, what only the account owner can do, and what could still get the app refused. Policy wording was read
`on 2026-11-03 from Google's help pages; check them again before submitting, because they change.
`
`Read this first: **parental-control apps get close review, and approval is not guaranteed.** See [the risks](#what-could-still-get-it-refused).
`
`## Done in the code
`
`| Requirement | What Harbor does |
`|---|---|
`| Release bundle | \`gradlew :app:bundleRelease\` makes the \`.aab\`, version name 1.0.0. Not debuggable. |
`| Target API | 36, which is the minimum for new apps since 31 August 2026. |
`| SMS and call log | Removed. Play allows them only for default handlers and a short list of exceptions; it lists "Family or device locator" and SMS alerts as invalid. Message alerts now come from notification access alone. |
`| Contacts | Removed. The permission was declared but never requested, so the "saved contact" flag was always false. Play restricts broad contact access from 27 January 2027 anyway. |
`| Monitoring declaration | \`isMonitoringTool = child_monitoring\` in the manifest. Play then shows a Play Protect notice on the child's phone. |
`| Consent before each sensitive permission | A separate screen with a deliberate tap before location, background location, usage access, notification access and the Accessibility service. |
`| Persistent notification | Shown while the phone shares its location, which setup requires. |
`| Account deletion | In the app (Settings), in the web app (Family), and at the public page below. Deleted server side by \`public.delete_my_account\`. |
`| Public documents | Built into the web app at every deploy: \`/privacy.html\`, \`/terms.html\`, \`/account-deletion.html\`. |
`
`## Only the account owner can do these
`
`1. **Developer account.** A personal account created after 13 November 2023 must run a closed test with **at least 12 testers opted in for 14 days in a row** before it can apply for production. Review of that application usually takes up to 7 days. An organisation account does not have this rule, but needs a D-U-N-S number. Plan around this: it is the longest step.
`2. **A real web address.** The privacy policy and deletion page currently live at \`harbor-family-dev.vercel.app\`, a development alias. Point a domain you intend to keep at the web app, then change the address in \`privacy-policy.md\` and in the Play Console.
`3. **Finish the legal documents.** The privacy policy and terms both still open with "Draft for review". Have them reviewed, ideally by a lawyer given that the app handles children's data, then remove that line.
`4. **An upload key.** Keep it outside the repository and back it up, and enrol in Play App Signing:
`   \`\`\`bash
`   keytool -genkeypair -v -keystore harbor-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias harbor
`   \`\`\`
`   Then build with \`HARBOR_KEYSTORE\` and \`HARBOR_KEYSTORE_PASSWORD\` set to it. Play signs what users install with its own key, so **phones on the App Distribution build must uninstall and pair again** to move to the Play version.
`5. **Store graphics.** A 512 by 512 icon, a 1024 by 500 feature graphic, and at least two phone screenshots.
`6. **A reviewer's login.** Play needs working credentials to reach the signed-in screens. Create a separate demo parent with a demo child and a paired phone. Never give it a real family.
`7. **Videos.** The background-location and Accessibility declarations each need a short video of the consent screen and Android's prompt. Location's must be 30 seconds or less.
`8. **Fill in the forms** below, then upload the bundle to internal testing first.
`
`## Store listing draft
`
`**Name:** Harbor Family
`
`**Short description:** Parental controls for your child's Android phone: location, screen time and safety alerts.
`
`**Full description:**
`
`> Harbor Family is built exclusively for parents and guardians to look after their own children. Install it on your child's phone, with their knowledge, and manage it from your own phone or the web.
`>
`> A notification stays on your child's phone, and Harbor explains on that phone what it shares and why.
`>
`> **See where your child is.** Your child's phone shares its location with you, in the background, on a family map. Parents can choose to share theirs too.
`>
`> **Set healthy limits.** See screen time by app, set daily limits and bedtime, pause the phone, and block apps. This uses Android's Accessibility service to see which app is open. It can't read what is on the screen.
`>
`> **Hear about possible concerns.** Harbor checks new notifications from messaging apps, on the child's phone, for signs of bullying, self-harm, adult content, drugs, threats or an unknown adult. You receive only the kind of concern, the app and the time, never the message or who sent it.
`>
`> **Check in.** Ask your child to check in, and answer their requests to use an app.
`>
`> Harbor must not be used to monitor anyone other than your own child. It is not for tracking a partner, another adult or anyone without their knowledge.
`
`**Category:** Parenting. **Contact email:** stalinmedrano@mytechtactics.com.
`
`## Data safety form
`
`Everything is encrypted in transit and none of it is sold or shared with third parties for advertising. Confirm this against your own processors before submitting: Supabase hosts the data, and Firebase carries the push notifications, whose content names no child, app or message.
`
`| Data | Collected | Why | Kept |
`|---|---|---|---|
`| Name, email (parent) | Yes, required | Account | Until deleted |
`| Precise location (child, and a parent who chooses to) | Yes | App functionality | Child: up to 30 days. Parent: latest only, 24 hours |
`| Installed apps and app activity (child) | Yes | App functionality | Up to 30 days |
`| Notification text (child) | Read on the phone, **not collected** | Checked for concerns on the device | Not stored or sent |
`| Concern category, app, time (child) | Yes | App functionality | Up to 30 days |
`| Device identifiers (push token) | Yes | Notifications | Until removed |
`
`**Deletion:** users can request it, at the address of \`/account-deletion.html\` on the final domain. Confirm the retention periods against the privacy policy when it is finalised.
`
`## Permission declarations
`
`**Background location.** Declare **one** feature only, because Play refuses a form that lists several. Suggested: *Lets a parent see where their child's phone is, on the family map, including when the app is closed.* The closest example on Play's page is child-safety geofencing, and it does not state outright that parental location sharing is accepted, so this is a real review question.
`
`**Accessibility.** Not an accessibility tool, and it must not claim to be. Purpose: *See which app is in front so it can apply the time limits, bedtime, pause and blocks a parent set.* It collects which apps were opened or blocked, and it can't read window content. The use must also be described in the listing, which the draft does.
`
`**Usage access and notification access.** I did not find or read a specific Play form for these. Answer whatever the Console asks, using the wording in the consent screens.
`
`## What could still get it refused
`
`These are judgement calls Play makes in review. None can be settled from the code.
`
`- **Reading notifications.** The Spyware policy lists "an application that steals information from other apps' notifications" as a violation. Harbor's defence is that it is disclosed, consented to, stays on the phone and sends only a category. **This is the likeliest objection.** If Play refuses it, message alerts may have to come out of the Play version.
`- **Blocking apps through Accessibility.** Play allows blocking the disabling or uninstalling of apps when a parent authorises it through a parental-control app. Blocking app launches is not explicitly addressed. Android's narrower parental-control route, Family Link style device administration, is what Play's "use narrower APIs" advice points towards.
`- **One app for both roles.** The same listing is installed on the parent's and the child's phone, and the child's side monitors. It is honest and allowed in principle, but reviewers may ask, and the Target audience answer must be adults, since Harbor is marketed to parents. Read the Families policy before answering.
`- **Dual use.** The listing and the app must keep saying this is only for your own children. Play rejects anything that could read as tracking a partner.
`- **The persistent notification.** An older policy announcement says monitoring apps must show one "at all times when the app is running". Harbor's runs while location is shared, which setup requires, but stops if location is turned off. Check the current Stalkerware section.
`
`**Pages not read for this:** the current Malware policy's Stalkerware section, the Families policy, the Permissions Declaration Form, and any specific rule on Usage Access or notification listeners. Read these before submitting.
`
`## Not done yet
`
`- **The uninstall guard is on hold and still does not work.** Do not mention it in the listing. Its wording in the app and documents should be checked before submission, because they still say removing an app needs a parent's OK.
`- **Code shrinking is off.** Play accepts this. It only makes the bundle larger, and turning it on needs a full pass through every screen.
`- **\`public.family_places\` (the old view)** can be dropped once no phone runs 0.36. The advisor reports it as an error until then.
