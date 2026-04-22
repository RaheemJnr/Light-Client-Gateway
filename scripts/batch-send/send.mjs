#!/usr/bin/env node

/**
 * CKB Batch Send Script — Lagos Meetup Faucet
 *
 * Reads claimed CKB addresses from the Google Sheet (via CSV export),
 * builds one transaction with N outputs (1,000 CKB each),
 * signs with faucet wallet private key, and sends to mainnet.
 *
 * Usage:
 *   PRIVATE_KEY=0x... node send.mjs              # live send
 *   PRIVATE_KEY=0x... DRY_RUN=true node send.mjs # dry run (no send)
 *
 * Or with a local CSV file:
 *   PRIVATE_KEY=0x... CSV_FILE=./claims.csv node send.mjs
 */

import { config, Indexer, RPC, helpers, commons, BI, hd } from "@ckb-lumos/lumos";

// --- Configuration ---
const CKB_RPC_URL = process.env.CKB_RPC_URL || "https://mainnet.ckbapp.dev/rpc";
const SHEET_CSV_URL = "https://docs.google.com/spreadsheets/d/1ufRJqM36eKOPc5YmQNtFbhAzhdJ1o19XFV5_IHzhBEM/export?format=csv";
const AMOUNT_PER_RECIPIENT = BI.from("100000000000"); // 1,000 CKB in shannons
const FAUCET_ADDRESS = "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqfx7c8l89wcf42vxgajjke9zdt7myeqrdqj6mqm6";
const DRY_RUN = process.env.DRY_RUN === "true";
const FEE_RATE = 1500; // shannons per KB

// Bech32 character set for CKB addresses
const BECH32_REGEX = /^ckb1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{42,}$/;

// --- Main ---
async function main() {
  // 1. Validate private key
  const privateKey = process.env.PRIVATE_KEY;
  if (!privateKey || !privateKey.startsWith("0x") || privateKey.length !== 66) {
    console.error("ERROR: Set PRIVATE_KEY env var (0x-prefixed, 64 hex chars)");
    console.error("Usage: PRIVATE_KEY=0x... node send.mjs");
    process.exit(1);
  }

  // 2. Initialize Lumos for mainnet
  config.initializeConfig(config.predefined.LINA);
  const rpc = new RPC(CKB_RPC_URL);
  const indexer = new Indexer(CKB_RPC_URL);

  // 3. Verify private key matches faucet address
  const fromLock = helpers.parseAddress(FAUCET_ADDRESS);
  const derivedArgs = hd.key.privateKeyToBlake160(privateKey);
  if (fromLock.args !== derivedArgs) {
    console.error("ERROR: Private key does not match faucet address");
    console.error(`  Expected args: ${fromLock.args}`);
    console.error(`  Derived args:  ${derivedArgs}`);
    process.exit(1);
  }
  console.log(`Faucet address: ${FAUCET_ADDRESS}`);

  // 4. Load claimed addresses
  const addresses = await loadClaimedAddresses();
  if (addresses.length === 0) {
    console.error("ERROR: No valid CKB addresses found in claims");
    process.exit(1);
  }
  console.log(`Found ${addresses.length} claimed addresses`);

  // 5. Calculate totals
  const totalAmount = AMOUNT_PER_RECIPIENT.mul(addresses.length);
  const totalCKB = totalAmount.div(BI.from("100000000"));
  console.log(`Total to send: ${totalCKB} CKB (${totalAmount} shannons)`);

  // 6. Check faucet balance
  const balance = await getBalance(indexer, fromLock);
  const balanceCKB = balance.div(BI.from("100000000"));
  console.log(`Faucet balance: ${balanceCKB} CKB`);

  if (balance.lt(totalAmount)) {
    console.error(`ERROR: Insufficient balance. Need ${totalCKB} CKB, have ${balanceCKB} CKB`);
    process.exit(1);
  }

  // 7. Build transaction
  console.log("\nBuilding transaction...");
  let txSkeleton = helpers.TransactionSkeleton({ cellProvider: indexer });

  for (let i = 0; i < addresses.length; i++) {
    txSkeleton = await commons.common.transfer(
      txSkeleton,
      [FAUCET_ADDRESS],
      addresses[i],
      AMOUNT_PER_RECIPIENT,
    );
    if ((i + 1) % 20 === 0) {
      console.log(`  Added output ${i + 1}/${addresses.length}`);
    }
  }

  // Pay fee
  txSkeleton = await commons.common.payFeeByFeeRate(
    txSkeleton,
    [FAUCET_ADDRESS],
    FEE_RATE,
  );

  const inputCount = txSkeleton.get("inputs").size;
  const outputCount = txSkeleton.get("outputs").size;
  console.log(`Transaction: ${inputCount} inputs, ${outputCount} outputs`);

  // 8. Sign
  console.log("Signing transaction...");
  txSkeleton = commons.common.prepareSigningEntries(txSkeleton);
  const signingEntries = txSkeleton.get("signingEntries").toArray();

  const signatures = [];
  for (const entry of signingEntries) {
    const sig = hd.key.signRecoverable(entry.message, privateKey);
    signatures.push(sig);
  }

  const signedTx = helpers.sealTransaction(txSkeleton, signatures);

  // 9. Print summary
  console.log("\n--- Transaction Summary ---");
  console.log(`Recipients: ${addresses.length}`);
  console.log(`Amount each: 1,000 CKB`);
  console.log(`Total: ${totalCKB} CKB`);
  console.log(`Inputs: ${inputCount}`);
  console.log(`Outputs: ${outputCount} (${addresses.length} recipients + change)`);

  if (DRY_RUN) {
    console.log("\n[DRY RUN] Transaction built and signed but NOT sent.");
    console.log("Remove DRY_RUN=true to send for real.");
    // Print first few outputs for verification
    console.log("\nFirst 5 recipients:");
    for (let i = 0; i < Math.min(5, addresses.length); i++) {
      console.log(`  ${i + 1}. ${addresses[i]}`);
    }
    process.exit(0);
  }

  // 10. Send
  console.log("\nSending transaction...");
  try {
    const txHash = await rpc.sendTransaction(signedTx, "passthrough");
    console.log(`\nSUCCESS! Transaction hash: ${txHash}`);
    console.log(`Track: https://explorer.nervos.org/transaction/${txHash}`);
  } catch (err) {
    console.error("\nFailed to send transaction:", err.message || err);
    process.exit(1);
  }
}

// --- Helpers ---

async function loadClaimedAddresses() {
  let csvText;

  // Try local file first, then Google Sheet
  const csvFile = process.env.CSV_FILE;
  if (csvFile) {
    const fs = await import("fs");
    csvText = fs.readFileSync(csvFile, "utf-8");
    console.log(`Reading claims from local file: ${csvFile}`);
  } else {
    console.log("Fetching claims from Google Sheet...");
    // Follow redirects manually for Google Sheets
    let url = SHEET_CSV_URL;
    let response = await fetch(url, { redirect: "follow" });
    if (!response.ok) {
      throw new Error(`Failed to fetch sheet: ${response.status} ${response.statusText}`);
    }
    csvText = await response.text();
  }

  // Parse CSV — find "CKB Address" column
  const lines = csvText.split("\n").map((l) => l.trim()).filter((l) => l);
  if (lines.length < 2) {
    console.error("ERROR: CSV has no data rows");
    return [];
  }

  // Parse header to find CKB Address column
  const headers = parseCSVLine(lines[0]);
  const addrCol = headers.findIndex(
    (h) => h.toLowerCase().trim() === "ckb address",
  );

  if (addrCol === -1) {
    console.error("ERROR: 'CKB Address' column not found in CSV headers");
    console.error("Headers found:", headers.join(", "));
    return [];
  }

  // Extract and validate addresses
  const addresses = [];
  const seen = new Set();
  let skipped = 0;

  for (let i = 1; i < lines.length; i++) {
    const cols = parseCSVLine(lines[i]);
    const addr = (cols[addrCol] || "").trim();

    if (!addr) continue;

    if (!BECH32_REGEX.test(addr)) {
      console.warn(`  SKIP row ${i + 1}: invalid address format: ${addr.substring(0, 20)}...`);
      skipped++;
      continue;
    }

    if (seen.has(addr)) {
      console.warn(`  SKIP row ${i + 1}: duplicate address`);
      skipped++;
      continue;
    }

    seen.add(addr);
    addresses.push(addr);
  }

  if (skipped > 0) {
    console.log(`Skipped ${skipped} invalid/duplicate entries`);
  }

  return addresses;
}

function parseCSVLine(line) {
  const result = [];
  let current = "";
  let inQuotes = false;

  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') {
      if (inQuotes && line[i + 1] === '"') {
        current += '"';
        i++;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (ch === "," && !inQuotes) {
      result.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  result.push(current);
  return result;
}

async function getBalance(indexer, lockScript) {
  let balance = BI.from(0);
  const collector = indexer.collector({ lock: lockScript, type: "empty" });
  for await (const cell of collector.collect()) {
    balance = balance.add(cell.cellOutput.capacity);
  }
  return balance;
}

// --- Run ---
main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
