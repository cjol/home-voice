// Run: npm run test:unwrap -w agent
import assert from "node:assert/strict";
import { unwrap, toolResultForModel } from "./unwrap.ts";

assert.deepEqual(unwrap({ content: [{ type: "text", text: "hi" }] }), "hi");
assert.deepEqual(unwrap({ content: [{ type: "text", text: "{\"a\":1}" }], structuredContent: { a: 2 } }), { a: 2 });
// execute returning nested envelopes from app tools
const nested = {
  content: [{ type: "text", text: JSON.stringify({ emails: { content: [{ type: "text", text: "[{\"subject\":\"Yo\"}]" }] } }) }],
};
assert.deepEqual(unwrap(nested), { emails: [{ subject: "Yo" }] });
assert.deepEqual(unwrap({ content: [{ type: "text", text: "boom" }], isError: true }), { error: "boom" });
// ordinary objects with a `content` field but other keys are left alone
assert.deepEqual(unwrap({ content: "x", id: 1 }), { content: "x", id: 1 });
assert.ok(toolResultForModel({ content: [{ type: "text", text: "x".repeat(20000) }] }).includes("truncated"));
console.log("unwrap: ok");
