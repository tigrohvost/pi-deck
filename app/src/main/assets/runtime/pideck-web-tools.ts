/**
 * Small, managed network tools for the phone-sized Pi runtime.
 *
 * PI//DECK deliberately starts Pi with --no-extensions, so this file is loaded explicitly from
 * the APK instead of trusting arbitrary packages from the user's Pi configuration. Requests are
 * bounded, abort-aware, and limited to fixed search/weather endpoints.
 */
import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const USER_AGENT = "PI-DECK/0.3 (+https://github.com/)";
const REQUEST_TIMEOUT_MS = 20_000;
const MAX_RESPONSE_BYTES = 256 * 1024;
const MAX_QUERY_CHARS = 500;
const MAX_RESULTS = 5;
const MAX_SNIPPET_CHARS = 520;

type SearchResult = {
	title: string;
	url: string;
	snippet: string;
};

function requestSignal(signal?: AbortSignal): AbortSignal {
	const timeout = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
	return signal ? AbortSignal.any([signal, timeout]) : timeout;
}

async function boundedResponseText(response: Response): Promise<string> {
	const declared = Number(response.headers.get("content-length"));
	if (Number.isFinite(declared) && declared > MAX_RESPONSE_BYTES) {
		throw new Error("Network response exceeds the PI//DECK size limit");
	}
	if (!response.body) return "";

	const reader = response.body.getReader();
	const chunks: Uint8Array[] = [];
	let total = 0;
	while (true) {
		const { done, value } = await reader.read();
		if (done) break;
		if (!value) continue;
		total += value.byteLength;
		if (total > MAX_RESPONSE_BYTES) {
			await reader.cancel();
			throw new Error("Network response exceeds the PI//DECK size limit");
		}
		chunks.push(value);
	}

	const merged = new Uint8Array(total);
	let offset = 0;
	for (const chunk of chunks) {
		merged.set(chunk, offset);
		offset += chunk.byteLength;
	}
	return new TextDecoder().decode(merged);
}

async function fetchText(
	url: string,
	init: RequestInit,
	signal?: AbortSignal,
): Promise<string> {
	const response = await fetch(url, {
		...init,
		headers: {
			"User-Agent": USER_AGENT,
			...init.headers,
		},
		signal: requestSignal(signal),
	});
	if (!response.ok) {
		throw new Error(`Network request failed with HTTP ${response.status}`);
	}
	return boundedResponseText(response);
}

function compact(value: string, maximum = MAX_SNIPPET_CHARS): string {
	const normalized = value.replace(/\s+/g, " ").trim();
	return normalized.length <= maximum
		? normalized
		: `${normalized.slice(0, maximum - 1).trimEnd()}…`;
}

function safeHttpUrl(value: string): string | undefined {
	try {
		const url = new URL(value);
		return url.protocol === "http:" || url.protocol === "https:"
			? url.toString()
			: undefined;
	} catch {
		return undefined;
	}
}

function exaPayload(body: string): string {
	for (const line of body.split("\n")) {
		if (!line.startsWith("data:")) continue;
		const raw = line.slice(5).trim();
		if (!raw) continue;
		try {
			const parsed = JSON.parse(raw) as {
				result?: {
					content?: Array<{ type?: string; text?: string }>;
					isError?: boolean;
				};
				error?: { message?: string };
			};
			if (parsed.error) throw new Error(parsed.error.message || "Search provider error");
			if (parsed.result?.isError) {
				throw new Error(
					parsed.result.content?.find((part) => part.type === "text")?.text
						|| "Search provider error",
				);
			}
			const text = parsed.result?.content?.find(
				(part) => part.type === "text" && typeof part.text === "string",
			)?.text;
			if (text) return text;
		} catch (error) {
			if (error instanceof SyntaxError) continue;
			throw error;
		}
	}

	try {
		const parsed = JSON.parse(body) as {
			result?: { content?: Array<{ type?: string; text?: string }> };
			error?: { message?: string };
		};
		if (parsed.error) throw new Error(parsed.error.message || "Search provider error");
		const text = parsed.result?.content?.find(
			(part) => part.type === "text" && typeof part.text === "string",
		)?.text;
		if (text) return text;
	} catch (error) {
		if (!(error instanceof SyntaxError)) throw error;
	}
	throw new Error("Search provider returned no readable content");
}

function parseExaResults(value: string): SearchResult[] {
	return value
		.split(/(?=^Title: )/m)
		.map((block) => {
			const title = compact(block.match(/^Title:\s*(.+)$/m)?.[1] || "", 220);
			const url = safeHttpUrl(block.match(/^URL:\s*(.+)$/m)?.[1]?.trim() || "");
			const contentStart = block.search(/\n(?:Text|Highlights):\s*\n/);
			const snippet = contentStart >= 0
				? compact(block.slice(contentStart).replace(/^\n(?:Text|Highlights):\s*\n/, ""))
				: "";
			return url && title ? { title, url, snippet } : undefined;
		})
		.filter((result): result is SearchResult => result !== undefined)
		.slice(0, MAX_RESULTS);
}

async function searchExa(query: string, signal?: AbortSignal): Promise<SearchResult[]> {
	const body = await fetchText(
		"https://mcp.exa.ai/mcp",
		{
			method: "POST",
			headers: {
				"Content-Type": "application/json",
				"Accept": "application/json, text/event-stream",
			},
			body: JSON.stringify({
				jsonrpc: "2.0",
				id: 1,
				method: "tools/call",
				params: {
					name: "web_search_exa",
					arguments: {
						query,
						numResults: MAX_RESULTS,
						livecrawl: "fallback",
						type: "auto",
						contextMaxCharacters: 6_000,
					},
				},
			}),
		},
		signal,
	);
	return parseExaResults(exaPayload(body));
}

function decodeHtml(value: string): string {
	const named: Record<string, string> = {
		amp: "&",
		apos: "'",
		gt: ">",
		hellip: "…",
		lt: "<",
		nbsp: " ",
		quot: "\"",
	};
	return value
		.replace(/<[^>]+>/g, " ")
		.replace(/&#(\d+);/g, (_match, decimal: string) =>
			String.fromCodePoint(Number(decimal)))
		.replace(/&#x([0-9a-f]+);/gi, (_match, hexadecimal: string) =>
			String.fromCodePoint(Number.parseInt(hexadecimal, 16)))
		.replace(/&([a-z]+);/gi, (match, name: string) => named[name.toLowerCase()] ?? match);
}

function duckDuckGoTarget(value: string): string | undefined {
	const decoded = decodeHtml(value);
	try {
		const redirect = new URL(decoded.startsWith("//") ? `https:${decoded}` : decoded);
		const target = redirect.hostname.endsWith("duckduckgo.com")
			? redirect.searchParams.get("uddg")
			: redirect.toString();
		return target ? safeHttpUrl(target) : undefined;
	} catch {
		return undefined;
	}
}

function parseDuckDuckGoResults(html: string): SearchResult[] {
	const matches = [
		...html.matchAll(
			/<a[^>]*class="[^"]*\bresult__a\b[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi,
		),
	];
	const results: SearchResult[] = [];
	for (let index = 0; index < matches.length && results.length < MAX_RESULTS; index++) {
		const match = matches[index];
		const url = duckDuckGoTarget(match[1]);
		const title = compact(decodeHtml(match[2]), 220);
		if (!url || !title) continue;
		const segmentStart = (match.index ?? 0) + match[0].length;
		const segmentEnd = matches[index + 1]?.index ?? Math.min(html.length, segmentStart + 5_000);
		const segment = html.slice(segmentStart, segmentEnd);
		const snippetMatch = segment.match(
			/class="[^"]*\bresult__snippet\b[^"]*"[^>]*>([\s\S]*?)<\/(?:a|div)>/i,
		);
		results.push({
			title,
			url,
			snippet: compact(decodeHtml(snippetMatch?.[1] || "")),
		});
	}
	return results;
}

async function searchDuckDuckGo(query: string, signal?: AbortSignal): Promise<SearchResult[]> {
	const url = new URL("https://html.duckduckgo.com/html/");
	url.searchParams.set("q", query);
	const body = await fetchText(
		url.toString(),
		{
			method: "GET",
			headers: {
				"Accept": "text/html,application/xhtml+xml",
				"Accept-Language": "ru,en;q=0.7",
			},
		},
		signal,
	);
	return parseDuckDuckGoResults(body);
}

async function webSearch(query: string, signal?: AbortSignal): Promise<{
	provider: string;
	results: SearchResult[];
}> {
	try {
		const results = await searchExa(query, signal);
		if (results.length > 0) return { provider: "Exa", results };
	} catch (error) {
		if (signal?.aborted) throw error;
	}
	const results = await searchDuckDuckGo(query, signal);
	if (results.length === 0) throw new Error("Web search returned no results");
	return { provider: "DuckDuckGo", results };
}

function formatSearchResults(provider: string, results: SearchResult[]): string {
	const lines = [`Провайдер поиска: ${provider}. Найдено: ${results.length}.`];
	for (let index = 0; index < results.length; index++) {
		const result = results[index];
		lines.push(
			"",
			`${index + 1}. ${result.title}`,
			`URL: ${result.url}`,
		);
		if (result.snippet) lines.push(`Фрагмент: ${result.snippet}`);
	}
	lines.push("", "Сформулируй ответ пользователю по этим данным и укажи использованные URL.");
	return lines.join("\n");
}

async function jsonRequest(url: URL, signal?: AbortSignal): Promise<any> {
	const body = await fetchText(
		url.toString(),
		{ method: "GET", headers: { "Accept": "application/json" } },
		signal,
	);
	try {
		return JSON.parse(body);
	} catch {
		throw new Error("Weather provider returned malformed JSON");
	}
}

function weatherDescription(code: unknown): string {
	if (typeof code !== "number") return "условия не указаны";
	if (code === 0) return "ясно";
	if (code <= 3) return "переменная облачность";
	if (code === 45 || code === 48) return "туман";
	if (code >= 51 && code <= 57) return "морось";
	if (code >= 61 && code <= 67) return "дождь";
	if (code >= 71 && code <= 77) return "снег";
	if (code >= 80 && code <= 82) return "ливни";
	if (code >= 85 && code <= 86) return "снегопад";
	if (code >= 95) return "гроза";
	return "условия не указаны";
}

function number(value: unknown, suffix: string): string {
	return typeof value === "number" && Number.isFinite(value)
		? `${Math.round(value * 10) / 10}${suffix}`
		: "нет данных";
}

async function lookupWeather(location: string, signal?: AbortSignal): Promise<string> {
	const geocoding = new URL("https://geocoding-api.open-meteo.com/v1/search");
	geocoding.searchParams.set("name", location);
	geocoding.searchParams.set("count", "1");
	geocoding.searchParams.set("language", "ru");
	geocoding.searchParams.set("format", "json");
	const placeData = await jsonRequest(geocoding, signal);
	const place = Array.isArray(placeData?.results) ? placeData.results[0] : undefined;
	if (
		!place
		|| typeof place.latitude !== "number"
		|| typeof place.longitude !== "number"
	) {
		throw new Error(`Место не найдено: ${location}`);
	}

	const forecastUrl = new URL("https://api.open-meteo.com/v1/forecast");
	forecastUrl.searchParams.set("latitude", String(place.latitude));
	forecastUrl.searchParams.set("longitude", String(place.longitude));
	forecastUrl.searchParams.set(
		"current",
		"temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m",
	);
	forecastUrl.searchParams.set(
		"daily",
		"temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
	);
	forecastUrl.searchParams.set("timezone", String(place.timezone || "auto"));
	forecastUrl.searchParams.set("forecast_days", "3");
	const forecast = await jsonRequest(forecastUrl, signal);
	const current = forecast?.current ?? {};
	const daily = forecast?.daily ?? {};
	const placeLabel = [place.name, place.admin1, place.country]
		.filter((value, index, values) => value && values.indexOf(value) === index)
		.join(", ");

	const lines = [
		`Источник: Open-Meteo. Место: ${placeLabel || location}.`,
		`Актуально на ${current.time || "неизвестное время"} (${forecast.timezone || place.timezone || "local"}).`,
		`Сейчас: ${weatherDescription(current.weather_code)}, ${number(current.temperature_2m, " °C")}; ощущается как ${number(current.apparent_temperature, " °C")}.`,
		`Влажность: ${number(current.relative_humidity_2m, "%")}; ветер: ${number(current.wind_speed_10m, " км/ч")}; осадки: ${number(current.precipitation, " мм")}.`,
		"Прогноз:",
	];
	const dates = Array.isArray(daily.time) ? daily.time : [];
	for (let index = 0; index < Math.min(3, dates.length); index++) {
		lines.push(
			`${dates[index]}: ${weatherDescription(daily.weather_code?.[index])}; `
				+ `${number(daily.temperature_2m_min?.[index], " °C")}…`
				+ `${number(daily.temperature_2m_max?.[index], " °C")}; `
				+ `вероятность осадков до ${number(daily.precipitation_probability_max?.[index], "%")}.`,
		);
	}
	lines.push(`URL: ${forecastUrl.toString()}`);
	return lines.join("\n");
}

export default function pideckWebTools(pi: ExtensionAPI) {
	pi.registerTool({
		name: "web_search",
		label: "Web search",
		description:
			"Search the live public web for current information and return compact results with source URLs.",
		promptSnippet: "Search the live web and return compact sourced results",
		promptGuidelines: [
			"Use web_search whenever the user explicitly asks to search the internet or needs current information; do not pretend to have searched.",
			"After web_search, answer from its results and include the relevant source URLs.",
		],
		parameters: Type.Object({
			query: Type.String({
				description: "A focused web search query",
				minLength: 1,
				maxLength: MAX_QUERY_CHARS,
			}),
		}),
		async execute(_toolCallId, params, signal) {
			const query = String(params.query ?? "").trim();
			if (!query || query.length > MAX_QUERY_CHARS) {
				throw new Error("Search query is empty or too long");
			}
			const result = await webSearch(query, signal);
			return {
				content: [{
					type: "text",
					text: formatSearchResults(result.provider, result.results),
				}],
				details: { provider: result.provider, resultCount: result.results.length },
			};
		},
	});

	pi.registerTool({
		name: "weather",
		label: "Weather",
		description:
			"Get current weather and a compact three-day forecast for a named place from Open-Meteo.",
		promptSnippet: "Get current weather and a three-day forecast for a place",
		promptGuidelines: [
			"Use weather for current conditions or forecasts instead of guessing or using a generic shell search.",
			"After weather, state the observation time and name Open-Meteo as the source.",
		],
		parameters: Type.Object({
			location: Type.String({
				description: "City or place name, for example Москва",
				minLength: 1,
				maxLength: 160,
			}),
		}),
		async execute(_toolCallId, params, signal) {
			const location = String(params.location ?? "").trim();
			if (!location || location.length > 160) {
				throw new Error("Weather location is empty or too long");
			}
			return {
				content: [{ type: "text", text: await lookupWeather(location, signal) }],
				details: { provider: "Open-Meteo", location },
			};
		},
	});
}
