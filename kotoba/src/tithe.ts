/**
 * crowdfunding kotoba — tithe math.
 *
 * The 10% tithe to the Public Fund is an immutable constitutional constant
 * (ADR-2605192100 §2). On-chain this is enforced atomically by TitheRouter.sol;
 * these pure helpers compute the same split client-side so the pledge payment
 * record matches what the contract will execute.
 *
 * All amounts are USDC base units (micros, 6 decimals) as bigint. Integer
 * division floors the tithe; the remainder accrues to the net side, so
 * tithe + net === gross exactly with no rounding leak.
 */

/** Constitutional tithe rate, as a permille (100 / 1000 = 10%). */
export const TITHE_PERMILLE = 100n;

export interface TitheSplit {
  gross: bigint;
  tithe: bigint;
  net: bigint;
}

/** Split a gross USDC amount (micros) into tithe (10%, floored) and net. */
export function splitTithe(grossMicros: bigint): TitheSplit {
  if (grossMicros < 0n) {
    throw new RangeError("[crowdfunding/tithe] gross amount must be non-negative");
  }
  const tithe = (grossMicros * TITHE_PERMILLE) / 1000n;
  return { gross: grossMicros, tithe, net: grossMicros - tithe };
}

/** Parse a decimal-string micros amount to bigint, rejecting non-integers. */
export function parseMicros(s: string): bigint {
  if (!/^\d+$/.test(s)) {
    throw new TypeError(
      `[crowdfunding/tithe] micros must be a non-negative integer string, got "${s}"`
    );
  }
  return BigInt(s);
}
