/**
 * Upload one Paper/Folia plugin jar as a single Modrinth version and a single
 * CurseForge file, tagged for every Minecraft version in
 * release/supported-minecraft.json.
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const support = JSON.parse(
  fs.readFileSync(path.join(root, "release", "supported-minecraft.json"), "utf8"),
);
const gameVersions = support.game_versions;
const loaders = support.loaders;

const version = process.env.VERSION;
const platforms = (process.env.PLATFORMS || "both").toLowerCase();
const doModrinth = platforms === "both" || platforms === "modrinth";
const doCurse = platforms === "both" || platforms === "curseforge";
const jar = process.env.JAR_PATH;

if (!version) throw new Error("VERSION is required");
if (!jar || !fs.existsSync(jar)) throw new Error("JAR_PATH missing or not found: " + jar);

function sectionForVersion(markdown, ver) {
  const lines = markdown.split(/\r?\n/);
  const start = lines.findIndex((line) => {
    const t = line.trim();
    return t === `## ${ver}` || t === `# ${ver}` || t.startsWith(`## ${ver} `) || t.startsWith(`# ${ver} `);
  });
  if (start < 0) return null;
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((line) => /^##\s/.test(line.trim()));
  const body = (end < 0 ? rest : rest.slice(0, end)).join("\n").trim();
  return body || null;
}

function resolveChangelog(ver) {
  const named = fs.readdirSync(root).filter(
    (f) =>
      f.toLowerCase().endsWith(`-${ver}-patchnotes.md`) ||
      f.toLowerCase() === `${ver}-patchnotes.md`,
  );
  for (const f of named) {
    const p = path.join(root, f);
    if (fs.existsSync(p)) return fs.readFileSync(p, "utf8");
  }
  for (const f of ["CHANGELOG.md", "PATCH_NOTES.md"]) {
    const p = path.join(root, f);
    if (!fs.existsSync(p)) continue;
    const full = fs.readFileSync(p, "utf8");
    return sectionForVersion(full, ver) || full;
  }
  return `Release ${ver}`;
}

const changelog = resolveChangelog(version);
const jarName = path.basename(jar);

(async () => {
  if (doModrinth) {
    if (!process.env.MODRINTH_TOKEN || !process.env.MODRINTH_ID) {
      throw new Error("MODRINTH_TOKEN and MODRINTH_ID are required");
    }
    const body = {
      name: version,
      version_number: version,
      changelog,
      dependencies: [],
      game_versions: gameVersions,
      version_type: "release",
      loaders,
      featured: true,
      status: "listed",
      project_id: process.env.MODRINTH_ID,
      file_parts: ["file_0"],
      primary_file: "file_0",
    };
    const form = new FormData();
    form.append("data", JSON.stringify(body));
    form.append("file_0", new Blob([fs.readFileSync(jar)]), jarName);
    const mrRes = await fetch("https://api.modrinth.com/v2/version", {
      method: "POST",
      headers: { Authorization: process.env.MODRINTH_TOKEN },
      body: form,
    });
    const mrText = await mrRes.text();
    if (!mrRes.ok) throw new Error("Modrinth " + mrRes.status + " " + mrText.slice(0, 500));
    console.log("Modrinth OK", version, loaders.join("+"), gameVersions.length, "MC versions");
  } else {
    console.log("Skipping Modrinth");
  }

  if (!doCurse) {
    console.log("Skipping CurseForge");
    return;
  }
  if (!process.env.CURSEFORGE_TOKEN || !process.env.CURSEFORGE_ID) {
    throw new Error("CURSEFORGE_TOKEN and CURSEFORGE_ID are required");
  }

  const gameVersionNames = [...gameVersions, "Client", "Server"];
  const meta = {
    changelog,
    changelogType: "markdown",
    displayName: version,
    gameVersionNames,
    releaseType: "release",
  };
  const cfForm = new FormData();
  cfForm.append("metadata", JSON.stringify(meta));
  cfForm.append("file", new Blob([fs.readFileSync(jar)]), jarName);
  const cfRes = await fetch(
    `https://minecraft.curseforge.com/api/projects/${process.env.CURSEFORGE_ID}/upload-file`,
    { method: "POST", headers: { "X-Api-Token": process.env.CURSEFORGE_TOKEN }, body: cfForm },
  );
  const cfText = await cfRes.text();
  if (!cfRes.ok) throw new Error("CurseForge " + cfRes.status + " " + cfText.slice(0, 500));
  console.log("CurseForge OK", version, gameVersionNames.length, "tags", jarName);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
