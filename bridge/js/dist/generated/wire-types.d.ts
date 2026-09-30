/** wire 面方法表（schema 同源）。facade 列 = 该命名空间的 TS 门面文件。 */
export declare const WIRE: {
    readonly a11y: {
        readonly facade: "a11y.ts";
        readonly methods: readonly ["bounds", "canPerformGestures", "children", "click", "copy", "desc", "dispose", "events", "findAll", "findOne", "findOneOrNull", "gesture", "longClick", "parent", "paste", "scroll", "setText", "text", "waitFor"];
    };
    readonly app: {
        readonly facade: "extras.ts";
        readonly methods: readonly ["currentPackage", "launch"];
    };
    readonly clipboard: {
        readonly facade: "clipboard.ts";
        readonly methods: readonly ["getText", "setText"];
    };
    readonly console: {
        readonly facade: "console.ts";
        readonly methods: readonly ["log"];
    };
    readonly datastore: {
        readonly facade: "datastore.ts";
        readonly methods: readonly ["clear", "contains", "get", "keys", "put", "remove"];
    };
    readonly device: {
        readonly facade: "extras.ts";
        readonly methods: readonly ["model", "sdkInt"];
    };
    readonly dialogs: {
        readonly facade: "extras.ts";
        readonly methods: readonly ["choose", "prompt"];
    };
    readonly engines: {
        readonly facade: "engines.ts";
        readonly methods: readonly ["channel", "channelClose", "channelDrain", "channelEmit", "exec", "heartbeat", "poolStats", "status", "stop"];
    };
    readonly floatingWindow: {
        readonly facade: "extras.ts";
        readonly methods: readonly ["close", "create"];
    };
    readonly images: {
        readonly facade: "images.ts";
        readonly methods: readonly ["crop", "decode", "findColor", "findFeature", "findImage", "matchTemplate", "release", "resize", "rotate", "toGrayscale"];
    };
    readonly notification: {
        readonly facade: "notification.ts";
        readonly methods: readonly ["canPost", "cancel", "post"];
    };
    readonly npm: {
        readonly facade: "npm.ts";
        readonly methods: readonly ["approvals", "audit", "ci", "dedupe", "events", "importOfflineBundle", "importTarball", "install", "list", "offlineGap", "prune", "remove", "requestApprove", "setRegistry"];
    };
    readonly power_manager: {
        readonly facade: "power.ts";
        readonly methods: readonly ["acquire", "release", "status"];
    };
    readonly screen: {
        readonly facade: "images.ts";
        readonly methods: readonly ["capture", "closeSession", "nextFrame", "recycle", "startCapturer"];
    };
    readonly sensors: {
        readonly facade: "sensors.ts";
        readonly methods: readonly ["drain", "isSupported", "register", "unregister", "unregisterAll"];
    };
    readonly settings: {
        readonly facade: "settings.ts";
        readonly methods: readonly ["canWrite", "getInt", "getString", "putInt", "putString"];
    };
    readonly shell: {
        readonly facade: "extras.ts";
        readonly methods: readonly ["exec", "shell"];
    };
    readonly workManager: {
        readonly facade: "workManager.ts";
        readonly methods: readonly ["cancel", "create", "list"];
    };
    readonly zip: {
        readonly facade: "zip.ts";
        readonly methods: readonly ["compress", "extract"];
    };
};
/** 命名空间字面量联合。 */
export type WireNamespace = keyof typeof WIRE;
/** `invoke(ns, m)` 的 m 在该 ns 下的合法字面量联合（收窄签名用的类型面）。 */
export type WireMethodOf<N extends WireNamespace> = (typeof WIRE)[N]['methods'][number];
/** 宿主收、facade 不发的 wire 名 → 所在 ns + 理由（schema aliases 同源）。 */
export declare const WIRE_ALIASES: Readonly<Record<string, {
    readonly ns: string;
    readonly why: string;
}>>;
/** invoke 之外的 JS 方法字面量入口（解析器按 form 映射正则；schema dynamicSinks 同源）。 */
export declare const WIRE_DYNAMIC_SINKS: Readonly<readonly {
    readonly file: string;
    readonly ns: string;
    readonly form: string;
    readonly why: string;
}[]>;
