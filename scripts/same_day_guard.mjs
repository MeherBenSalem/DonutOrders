#!/usr/bin/env node
// NightBeam same-day release guard.
//
// Maher's rule: never release a second update of the same mod on the same calendar day. This script runs at the
// start of Publish (and again right before the upload) and fails the run when this mod already had a release today,
// "today" being the calendar day in Africa/Tunis. NightBeam publishes to CurseForge only (Maher, 24 Nov 2026), and the
// guard looks in two places, because each can lag or miss a release:
//   1. CurseForge: the project's files (Core API, needs CURSEFORGE_API_KEY; files still in review may not show).
//   2. GitHub: earlier successful Publish runs of this repo whose upload step actually ran (not dry runs). The upload
//      step was called "Upload Modrinth + CurseForge" before 24 Nov 2026, so both names count.
// It fails closed: if a source can't be read on a real release, the release stops. On a dry run it only reports.
//
// Env: CURSEFORGE_ID, CURSEFORGE_API_KEY, GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_ID,
//      GITHUB_API_URL, GITHUB_STEP_SUMMARY, PUBLISH_WORKFLOW (file name, default publish.yml),
//      UPLOAD_STEP (upload step names separated by "|", default DEFAULT_UPLOAD_STEPS), RELEASE_TZ (default Africa/Tunis),
//      DRY_RUN ("true" to report only).

import { appendFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export const DEFAULT_TZ = "Africa/Tunis";
export const DEFAULT_UPLOAD_STEPS = "Upload CurseForge|Upload Modrinth + CurseForge";

export function dayIn(date, timeZone = DEFAULT_TZ) {
  // en-CA formats as YYYY-MM-DD.
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(
    new Date(date),
  );
}

async function getJson(fetchImpl, url, headers = {}) {
  const res = await fetchImpl(url, { headers: { Accept: "application/json", "User-Agent": "nightbeam-publish-guard", ...headers } });
  if (!res.ok) throw new Error(`${url} answered HTTP ${res.status}`);
  return res.json();
}

export async function checkCurseForge({ fetchImpl, id, apiKey, today, timeZone }) {
  const body = await getJson(fetchImpl, `https://api.curseforge.com/v1/mods/${encodeURIComponent(id)}/files?pageSize=50&index=0`, {
    "x-api-key": apiKey,
  });
  return (body.data || [])
    .filter((f) => dayIn(f.fileDate, timeZone) === today)
    .map((f) => `CurseForge ${f.fileName} (${f.fileDate})`);
}

export async function checkGitHub({ fetchImpl, api, repo, token, workflow, uploadStep, currentRunId, today, timeZone }) {
  const headers = { Authorization: `Bearer ${token}`, "X-GitHub-Api-Version": "2022-11-28" };
  // Runs created in the last 48 hours cover "today" in any time zone; the day test below is done in RELEASE_TZ.
  const since = new Date(Date.now() - 48 * 3600 * 1000).toISOString().slice(0, 10);
  const runs = await getJson(
    fetchImpl,
    `${api}/repos/${repo}/actions/workflows/${encodeURIComponent(workflow)}/runs?per_page=50&created=%3E%3D${since}`,
    headers,
  );
  const names = new Set(String(uploadStep).split("|").map((n) => n.trim()).filter(Boolean));
  const hits = [];
  for (const run of runs.workflow_runs || []) {
    if (String(run.id) === String(currentRunId)) continue;
    if (dayIn(run.run_started_at || run.created_at, timeZone) !== today) continue;
    const jobs = await getJson(fetchImpl, `${api}/repos/${repo}/actions/runs/${run.id}/jobs?per_page=50`, headers);
    const uploaded = (jobs.jobs || []).some((j) =>
      (j.steps || []).some((s) => names.has(s.name) && s.conclusion === "success"),
    );
    // A run still in progress with its upload step running counts too: it is about to release.
    const uploading = (jobs.jobs || []).some((j) =>
      (j.steps || []).some((s) => names.has(s.name) && s.status === "in_progress"),
    );
    if (uploaded || uploading) hits.push(`GitHub Publish run ${run.id} (${run.run_started_at || run.created_at}, ${uploaded ? "uploaded" : "uploading"})`);
  }
  return hits;
}

export async function runGuard(env = process.env, { fetchImpl = fetch, now = new Date(), log = console.log } = {}) {
  const timeZone = env.RELEASE_TZ || DEFAULT_TZ;
  const today = dayIn(now, timeZone);
  const dryRun = String(env.DRY_RUN || "").toLowerCase() === "true";
  const hits = [];
  const problems = [];

  const sources = [
    [
      "CurseForge",
      env.CURSEFORGE_ID && env.CURSEFORGE_API_KEY,
      () => checkCurseForge({ fetchImpl, id: env.CURSEFORGE_ID, apiKey: env.CURSEFORGE_API_KEY, today, timeZone }),
    ],
    [
      "GitHub",
      env.GITHUB_TOKEN && env.GITHUB_REPOSITORY,
      () =>
        checkGitHub({
          fetchImpl,
          api: env.GITHUB_API_URL || "https://api.github.com",
          repo: env.GITHUB_REPOSITORY,
          token: env.GITHUB_TOKEN,
          workflow: env.PUBLISH_WORKFLOW || "publish.yml",
          uploadStep: env.UPLOAD_STEP || DEFAULT_UPLOAD_STEPS,
          currentRunId: env.GITHUB_RUN_ID,
          today,
          timeZone,
        }),
    ],
  ];

  for (const [name, configured, check] of sources) {
    if (!configured) {
      problems.push(`${name}: not configured (missing ID, key or token)`);
      continue;
    }
    try {
      const found = await check();
      hits.push(...found);
      log(`${name}: ${found.length ? found.join("; ") : "no release today"}`);
    } catch (err) {
      problems.push(`${name}: could not be read (${err.message})`);
    }
  }

  const lines = [`### Same-day guard (${today}, ${timeZone})`, ""];
  let ok = true;
  if (hits.length) {
    lines.push(`This mod was already released today, so it can't be released again until tomorrow:`, ...hits.map((h) => `- ${h}`));
    ok = false;
  }
  if (problems.length) {
    lines.push("", "Couldn't check every source, so the release is refused to be safe:", ...problems.map((p) => `- ${p}`));
    ok = false;
  }
  if (ok) lines.push("No release of this mod today. OK to publish.");
  if (!ok && dryRun) lines.push("", "Dry run: reported only. A real release would stop here.");

  const summary = lines.join("\n");
  log(summary);
  if (env.GITHUB_STEP_SUMMARY) appendFileSync(env.GITHUB_STEP_SUMMARY, summary + "\n");
  if (!ok && dryRun) log(`::warning title=Same-day guard::${hits.length ? "Already released today" : "Not every source could be checked"}; a real release would stop here.`);
  if (!ok && !dryRun) log(`::error title=Same-day guard::${hits.length ? "Already released today (" + hits[0] + ")" : problems[0]}`);
  return { ok, blocked: !ok && !dryRun, today, hits, problems };
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const result = await runGuard();
  process.exit(result.blocked ? 1 : 0);
}
