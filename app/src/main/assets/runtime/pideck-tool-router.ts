/**
 * Keeps the small local model's initial tool schema proportional to the task.
 *
 * Pi registers every trusted bundled tool, but only the profile's compact core is active for a
 * normal turn. Explicit current-data requests activate the matching managed tools before Pi
 * builds the prompt. The model can load a remaining optional group when the core is insufficient.
 * No route can cross the Android-selected access profile.
 */

import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export type AccessProfile = "read_only" | "confirm_changes" | "autonomous";
export type AgentMode = "chat" | "agent";
export type ToolCapability = "files" | "web" | "weather" | "exact_edit";

const LOADER_TOOL = "pideck_load_tools";
export const INTERNAL_RETRY_PREFIX = "[[PI//DECK:ANSWER_RETRY]]\n";

const CORE_TOOLS: Record<AccessProfile, readonly string[]> = {
	read_only: ["read", "grep", "find", "ls"],
	confirm_changes: [
		"read",
		"grep",
		"find",
		"ls",
		"pideck_bash",
		"pideck_write",
		"pideck_replace_lines",
	],
	autonomous: ["read", "bash", "write", "pideck_replace_lines", "run_tests"],
};

const OPTIONAL_TOOLS: Record<AccessProfile, Record<ToolCapability, readonly string[]>> = {
	read_only: {
		files: [],
		web: ["web_search", "web_fetch"],
		weather: ["weather"],
		exact_edit: [],
	},
	confirm_changes: {
		files: [],
		web: ["web_search", "web_fetch"],
		weather: ["weather"],
		exact_edit: ["pideck_edit"],
	},
	autonomous: {
		files: ["grep", "find", "ls"],
		web: ["web_search", "web_fetch"],
		weather: ["weather"],
		exact_edit: ["edit"],
	},
};

const WEB_CUES = [
	"поищи в сети",
	"найди в сети",
	"посмотри в сети",
	"проверь в сети",
	"поиск в сети",
	"поищи в интернете",
	"найди в интернете",
	"посмотри в интернете",
	"проверь в интернете",
	"поиск в интернете",
	"поищи онлайн",
	"найди онлайн",
	"search the web",
	"search online",
	"browse the web",
	"look up online",
	"find online",
] as const;

const WEATHER_CUES = [
	"какая погод",
	"погода в ",
	"погоду в ",
	"погоды в ",
	"погода на ",
	"прогноз погод",
	"сейчас",
	"weather in ",
	"weather for ",
	"forecast in ",
	"forecast for ",
] as const;

function unique(values: readonly string[]): string[] {
	return [...new Set(values)];
}

function configuredProfile(): AccessProfile {
	const value = process.env.PIDECK_ACCESS_PROFILE;
	if (value === "read_only" || value === "confirm_changes" || value === "autonomous") {
		return value;
	}
	throw new Error("PI//DECK tool router has no valid access profile");
}

function configuredMode(): AgentMode {
	const value = process.env.PIDECK_AGENT_MODE;
	if (value === "chat" || value === "agent") return value;
	throw new Error("PI//DECK tool router has no valid agent mode");
}

export function coreTools(profile: AccessProfile): string[] {
	return [...CORE_TOOLS[profile], LOADER_TOOL];
}

export function optionalCapabilities(profile: AccessProfile): ToolCapability[] {
	return (Object.keys(OPTIONAL_TOOLS[profile]) as ToolCapability[]).filter(
		(capability) => OPTIONAL_TOOLS[profile][capability].length > 0,
	);
}

export function detectCapabilities(text: string): ToolCapability[] {
	const candidate = text.toLocaleLowerCase().replace(/\s+/g, " ").trim();
	const webRequested = WEB_CUES.some((cue) => candidate.includes(cue));
	const urlProvided = /(?:^|\s)https?:\/\/\S+/i.test(text);
	const weatherMentioned = /(?:^|[^\p{L}\p{N}_])(?:погод\p{L}*|weather|forecast)(?:$|[^\p{L}\p{N}_])/u
		.test(candidate);
	const weatherRequested = weatherMentioned && (
		webRequested || WEATHER_CUES.some((cue) => candidate.includes(cue))
	);
	const result: ToolCapability[] = [];
	if (webRequested || urlProvided) result.push("web");
	if (weatherRequested) result.push("weather");
	return result;
}

export function routeInput(
	text: string,
	streamingBehavior?: "steer" | "followUp",
): {
	text: string;
	capabilities: ToolCapability[];
	additive: boolean;
	transformed: boolean;
} {
	const transformed = text.startsWith(INTERNAL_RETRY_PREFIX);
	const routedText = transformed ? text.slice(INTERNAL_RETRY_PREFIX.length) : text;
	return {
		text: routedText,
		capabilities: detectCapabilities(routedText),
		// A retry is an idle RPC prompt only because Pi has already settled. It still
		// belongs to the previous task, so retain every optional tool that task enabled.
		additive: transformed || streamingBehavior !== undefined,
		transformed,
	};
}

export default function pideckToolRouter(pi: ExtensionAPI) {
	const profile = configuredProfile();
	const mode = configuredMode();
	const capabilities = optionalCapabilities(profile);
	const allowed = new Set([
		...CORE_TOOLS[profile],
		...capabilities.flatMap((capability) => OPTIONAL_TOOLS[profile][capability]),
		LOADER_TOOL,
	]);

	function activate(requested: readonly ToolCapability[], additive: boolean): string[] {
		if (mode === "chat") {
			pi.setActiveTools([]);
			return [];
		}
		const base = additive
			? pi.getActiveTools().filter((name) => allowed.has(name))
			: coreTools(profile);
		const additions = requested.flatMap((capability) =>
			OPTIONAL_TOOLS[profile][capability] ?? []);
		const active = unique([...base, ...additions]).filter((name) => allowed.has(name));
		pi.setActiveTools(active);
		return active;
	}

	const capabilitySchema = Type.Union(
		capabilities.map((capability) => Type.Literal(capability)),
	);
	pi.registerTool({
		name: LOADER_TOOL,
		label: "load optional tools",
		description:
			"Enable one optional capability only when the active tools cannot finish the task.",
		parameters: Type.Object({
			capability: capabilitySchema,
		}),
		async execute(_toolCallId, params) {
			const capability = String(params.capability) as ToolCapability;
			if (!capabilities.includes(capability)) {
				throw new Error(`Capability is unavailable in ${profile}: ${capability}`);
			}
			const before = new Set(pi.getActiveTools());
			const active = activate([capability], true);
			const added = active.filter((name) => !before.has(name));
			return {
				content: [{
					type: "text" as const,
					text: added.length > 0
						? `Enabled: ${added.join(", ")}`
						: `Already enabled: ${capability}`,
				}],
				details: { capability, added },
			};
		},
	});

	pi.on("session_start", () => {
		activate([], false);
	});

	pi.on("input", (event) => {
		// A queued correction belongs to the active task. Add what it needs without removing a
		// tool that may be referenced by the in-flight conversation. A bridge retry crosses an
		// idle boundary deliberately and carries a stripped internal marker for the same reason.
		const routed = routeInput(event.text, event.streamingBehavior);
		activate(routed.capabilities, routed.additive);
		return routed.transformed
			? { action: "transform", text: routed.text, images: event.images }
			: { action: "continue" };
	});

	pi.on("tool_call", (event) => {
		if (!allowed.has(event.toolName) || mode === "chat") {
			return {
				block: true,
				reason: "Tool is outside the active PI//DECK access profile",
			};
		}
		return undefined;
	});
}
