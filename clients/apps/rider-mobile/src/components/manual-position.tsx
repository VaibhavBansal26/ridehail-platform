import type { Fix } from "@ridehail/api";
import { useState } from "react";
import { View } from "react-native";
import { Button, Card, ErrorNote, Field, Muted } from "@/components/ui";

/** Set the position by hand: shown when the phone cannot supply one, and handy in a simulator with no route. */
export function ManualPosition({ value, onChange }: { value: Fix | null; onChange: (fix: Fix | null) => void }) {
  const [lat, setLat] = useState(String(value?.lat ?? 12.9716));
  const [lon, setLon] = useState(String(value?.lon ?? 77.5946));
  const [error, setError] = useState<string | null>(null);

  function apply() {
    const la = Number(lat), lo = Number(lon);
    if (!Number.isFinite(la) || !Number.isFinite(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180) {
      setError("Enter a latitude between -90 and 90 and a longitude between -180 and 180.");
      return;
    }
    setError(null);
    onChange({ lat: la, lon: lo });
  }

  return (
    <Card title={value ? "Using a position you set" : "Set position by hand"}>
      <View style={{ flexDirection: "row", gap: 8 }}>
        <View style={{ flex: 1 }}><Field label="Latitude" value={lat} onChangeText={setLat} keyboardType="numbers-and-punctuation" /></View>
        <View style={{ flex: 1 }}><Field label="Longitude" value={lon} onChangeText={setLon} keyboardType="numbers-and-punctuation" /></View>
      </View>
      {error ? <ErrorNote>{error}</ErrorNote> : null}
      <Button label="Use this position" variant="secondary" onPress={apply} />
      {value ? <Button label="Use GPS" variant="secondary" onPress={() => onChange(null)} /> : <Muted>Normally your phone&apos;s GPS is used.</Muted>}
    </Card>
  );
}
