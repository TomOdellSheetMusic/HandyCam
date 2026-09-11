#!/usr/bin/env node
// Generate obtainium.json for one-click app import.
/* eslint-disable no-console */

import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import process from 'node:process';

const version = process.argv[2];
const tag = process.argv[3] || `v${version}`;
const outputPath = process.argv[4] || 'obtainium.json';

if (!version) {
  console.error('Usage: node scripts/obtainium-generate.js <version> [tag] [output-path]');
  process.exit(1);
}

const GITHUB_REPO = 'TomOdellSheetMusic/HandyCam';
const APK_NAME = `HandyCam-${version}-universal.apk`;
const APK_URL = `https://github.com/${GITHUB_REPO}/releases/download/${tag}/${APK_NAME}`;
const isNightly = tag === 'nightly';
const appId = 'com.example.handycam';

// Obtainium fills in every other setting from its own defaults on import.
const additionalSettings = {
  about: 'A smartphone webcam client for OBS',
  // The nightly tag name never changes, so the version has to come from the date instead.
  ...(isNightly && {
    includePrereleases: true,
    useLatestAssetDateAsReleaseDate: true,
    releaseDateAsVersion: true,
    versionDetection: false,
  }),
};

const config = {
  apps: [
    {
      id: appId,
      url: `https://github.com/${GITHUB_REPO}`,
      author: 'TomOdellSheetMusic',
      name: isNightly ? 'HandyCam Nightly' : 'HandyCam',
      installedVersion: null,
      latestVersion: version,
      apkUrls: JSON.stringify([[APK_NAME, APK_URL]]),
      otherAssetUrls: '[]',
      preferredApkIndex: 0,
      additionalSettings: JSON.stringify(additionalSettings),
      lastUpdateCheck: null,
      pinned: false,
      categories: ['Video'],
      releaseDate: null,
      changeLog: null,
      overrideSource: 'GitHub',
      allowIdChange: false,
      pendingRepoRenameUrl: null,
    },
  ],
};

writeFileSync(resolve(outputPath), JSON.stringify(config, null, 2) + '\n');
console.log(`Generated ${outputPath}`);