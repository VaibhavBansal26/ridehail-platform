import { type MapState, mapPageHtml, mapStateScript } from "@ridehail/api";
import { type ComponentType, type Ref, useEffect, useMemo, useRef, useState } from "react";
import { View } from "react-native";
import { WebView as NativeWebView, type WebViewMessageEvent, type WebViewProps } from "react-native-webview";
import { useTheme } from "@/components/ui";
import { MAP_ATTRIBUTION, MAP_TILE_URL } from "@/lib/map-settings";

/** The one thing the map does to its WebView. react-native-webview's class typing does not fit React 19's JSX types, so only this much is typed. */
type MapWebView = { injectJavaScript: (script: string) => void };
const Web = NativeWebView as unknown as ComponentType<WebViewProps & { ref?: Ref<MapWebView> }>;

/** The page's own address: it is what the WebView reports as the page, and the tile server sees it as the referrer (OSM's tile policy asks for one). */
export const MAP_ORIGIN = "https://map.ridehail.app";

/**
 * The map card: pickup, drop-off, the road between them, and the vehicle moving. It is a small web page (Leaflet) in a WebView, so it needs
 * no native map library and no Google or Apple map key; the page and its pictures are the shared ones in @ridehail/api.
 */
export function RideMap({ height = 220, label = "Map of your ride", pickup, drop, car, route }: MapState & { height?: number; label?: string }) {
  const t = useTheme();
  const web = useRef<MapWebView>(null);
  const [ready, setReady] = useState(false);
  const html = useMemo(() => mapPageHtml({ tileUrl: MAP_TILE_URL, attribution: MAP_ATTRIBUTION }), []);
  const script = mapStateScript({ pickup, drop, car, route });

  // the page says "ready" once it can take a state; after that every change of what to show is sent to it
  useEffect(() => {
    if (ready) web.current?.injectJavaScript(script);
  }, [ready, script]);

  const onMessage = (e: WebViewMessageEvent) => {
    if (e.nativeEvent.data === "ready") setReady(true);
  };

  return (
    <View accessible accessibilityLabel={label} style={{ height, borderRadius: 12, overflow: "hidden", borderWidth: 1, borderColor: t.line, backgroundColor: t.surface }}>
      <Web
        ref={web}
        source={{ html, baseUrl: MAP_ORIGIN }}
        originWhitelist={["https://*", "about:*"]}
        javaScriptEnabled
        domStorageEnabled={false}
        scrollEnabled={false}
        bounces={false}
        overScrollMode="never"
        onMessage={onMessage}
        // the map shows a map and nothing else: no link in it (the attribution's, say) may take the card to another page
        onShouldStartLoadWithRequest={(request) => request.url === "about:blank" || request.url.startsWith(MAP_ORIGIN) || request.isTopFrame === false}
      />
    </View>
  );
}
