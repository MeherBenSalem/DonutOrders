import { test } from "node:test";
import assert from "node:assert/strict";
import { dayIn, runGuard } from "./same_day_guard.mjs";

const NOW = new Date("2026-11-23T10:00:00Z"); // 11:00 in Tunis

function fakeFetch(routes) {
  return async (url) => {
    for (const [prefix, body] of routes) {
      if (url.startsWith(prefix)) {
        if (body instanceof Error) return { ok: false, status: 500, json: async () => ({}) };
        return { ok: true, status: 200, json: async () => body };
      }
    }
    throw new Error(`unexpected ${url}`);
  };
}

const ENV = {
  CURSEFORGE_ID: "123",
  CURSEFORGE_API_KEY: "k",
  GITHUB_TOKEN: "t",
  GITHUB_REPOSITORY: "o/r",
  GITHUB_RUN_ID: "999",
};

const quiet = () => {};

test("dayIn uses Africa/Tunis", () => {
  assert.equal(dayIn("2026-11-22T23:30:00Z"), "2026-11-23"); // 00:30 in Tunis
  assert.equal(dayIn("2026-11-23T22:59:00Z"), "2026-11-23");
  assert.equal(dayIn("2026-11-23T23:01:00Z"), "2026-11-24");
});

const clean = [
  ["https://api.curseforge.com/", { data: [{ fileName: "a.jar", fileDate: "2026-11-20T09:00:00Z" }] }],
  ["https://api.github.com/repos/o/r/actions/workflows/", { workflow_runs: [] }],
];

test("never asks Modrinth (CurseForge only since 24 Nov 2026)", async () => {
  const seen = [];
  const f = fakeFetch(clean);
  const r = await runGuard(ENV, { fetchImpl: async (url, o) => (seen.push(url), f(url, o)), now: NOW, log: quiet });
  assert.equal(r.ok, true);
  assert.ok(seen.every((u) => !u.includes("modrinth")));
});

test("passes when nothing was released today", async () => {
  const r = await runGuard(ENV, { fetchImpl: fakeFetch(clean), now: NOW, log: quiet });
  assert.equal(r.ok, true);
  assert.equal(r.blocked, false);
});

test("blocks on a CurseForge file from today", async () => {
  const routes = [["https://api.curseforge.com/", { data: [{ fileName: "b.jar", fileDate: "2026-11-23T08:00:00Z" }] }], clean[1]];
  const r = await runGuard(ENV, { fetchImpl: fakeFetch(routes), now: NOW, log: quiet });
  assert.equal(r.blocked, true);
});

test("blocks on an earlier Publish run today whose upload step succeeded, ignores dry runs", async () => {
  const routes = [
    clean[0],
    [
      "https://api.github.com/repos/o/r/actions/workflows/",
      {
        workflow_runs: [
          { id: 1, run_started_at: "2026-11-23T07:00:00Z" },
          { id: 2, run_started_at: "2026-11-23T08:00:00Z" },
          { id: 3, run_started_at: "2026-11-23T09:00:00Z" },
          { id: 999, run_started_at: "2026-11-23T10:00:00Z" },
        ],
      },
    ],
    ["https://api.github.com/repos/o/r/actions/runs/1/jobs", { jobs: [{ steps: [{ name: "Upload Modrinth + CurseForge", conclusion: "skipped" }] }] }],
    ["https://api.github.com/repos/o/r/actions/runs/2/jobs", { jobs: [{ steps: [{ name: "Upload Modrinth + CurseForge", conclusion: "success" }] }] }],
    ["https://api.github.com/repos/o/r/actions/runs/3/jobs", { jobs: [{ steps: [{ name: "Upload CurseForge", conclusion: "success" }] }] }],
  ];
  const r = await runGuard(ENV, { fetchImpl: fakeFetch(routes), now: NOW, log: quiet });
  assert.equal(r.blocked, true);
  assert.equal(r.hits.length, 2);
  assert.match(r.hits[0], /run 2/);
  assert.match(r.hits[1], /run 3/);
});

test("fails closed when a source can't be read", async () => {
  const routes = [["https://api.curseforge.com/", new Error("down")], ...clean.slice(1)];
  const r = await runGuard(ENV, { fetchImpl: fakeFetch(routes), now: NOW, log: quiet });
  assert.equal(r.blocked, true);
  assert.equal(r.problems.length, 1);
});

test("fails closed when an ID is missing", async () => {
  const r = await runGuard({ ...ENV, CURSEFORGE_ID: "" }, { fetchImpl: fakeFetch(clean), now: NOW, log: quiet });
  assert.equal(r.blocked, true);
});

test("dry run reports but does not block", async () => {
  const routes = [["https://api.curseforge.com/", { data: [{ fileName: "c.jar", fileDate: "2026-11-23T09:00:00Z" }] }], ...clean.slice(1)];
  const r = await runGuard({ ...ENV, DRY_RUN: "true" }, { fetchImpl: fakeFetch(routes), now: NOW, log: quiet });
  assert.equal(r.ok, false);
  assert.equal(r.blocked, false);
});
