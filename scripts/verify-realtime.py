#!/usr/bin/env python3
"""
Checks that realtime works ACROSS application instances in a running `docker compose up` stack.

It plays a rider and a customer who are deliberately connected to DIFFERENT instances, so every push has to travel through
RabbitMQ, and it checks that the rate limits are shared between the instances through Redis. Needs: pip install websocket-client

    python3 scripts/verify-realtime.py                        # defaults: instance A on 8081, B on 8082, gateway on 8080
    python3 scripts/verify-realtime.py --a 8091 --b 8092 --gateway 8089

Run the stack with a LOW sustained location rate so the shared limit is easy to see:  LOCATION_RATE=0.2 docker compose up --build
"""
import argparse, http.client, json, queue, sys, threading, time, urllib.error, urllib.request, uuid

try:
    import websocket  # websocket-client
except ImportError:
    sys.exit("This script needs the 'websocket-client' package:  pip install websocket-client")

failures = []


def check(ok, what):
    print(("  PASS  " if ok else "  FAIL  ") + what)
    if not ok:
        failures.append(what)
    return ok


class Api:
    def __init__(self, port):
        self.base = f"http://localhost:{port}"

    def call(self, method, path, token=None, body=None):
        req = urllib.request.Request(self.base + path, method=method, data=None if body is None else json.dumps(body).encode())
        if body is not None:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                raw = r.read().decode()
                return r.status, (json.loads(raw) if raw else None), r.headers
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            return e.code, (json.loads(raw) if raw.startswith(("{", "[")) else raw), e.headers
        except (urllib.error.URLError, http.client.HTTPException, ConnectionError, TimeoutError, OSError):
            return 0, None, {}          # not reachable (yet): callers see status 0, never a stack trace

    def wait_until_healthy(self, seconds):
        deadline = time.time() + seconds
        while time.time() < deadline:
            if self.call("GET", "/actuator/health")[0] == 200:
                return True
            time.sleep(2)
        return False


class Stomp:
    """A minimal STOMP-over-WebSocket client, enough to behave like a mobile app."""

    def __init__(self, port, token):
        self.ws = websocket.create_connection(f"ws://localhost:{port}/ws", timeout=10)
        self.receipts, self.messages = queue.Queue(), queue.Queue()
        self._send("CONNECT", {"accept-version": "1.2", "host": "localhost", "Authorization": "Bearer " + token})
        first = self.ws.recv()
        if not first.startswith("CONNECTED"):
            raise RuntimeError("not connected: " + first[:80])
        self.alive = True
        threading.Thread(target=self._reader, daemon=True).start()

    def _send(self, cmd, headers, body=""):
        self.ws.send(cmd + "\n" + "".join(f"{k}:{v}\n" for k, v in headers.items()) + "\n" + body + "\0")

    def _reader(self):
        try:
            while True:
                data = self.ws.recv()
                if data.startswith("RECEIPT"):
                    self.receipts.put(data)
                elif data.startswith("MESSAGE"):
                    self.messages.put(json.loads(data.split("\n\n", 1)[1].rstrip("\0")))
        except Exception:
            self.alive = False

    def subscribe(self, destination):
        rid = "r-" + uuid.uuid4().hex[:8]
        self._send("SUBSCRIBE", {"id": "s-" + rid, "destination": destination, "receipt": rid})
        deadline = time.time() + 8
        while time.time() < deadline:
            try:
                if rid in self.receipts.get(timeout=0.5):
                    return True
            except queue.Empty:
                pass
        return False

    def wait_for(self, type_, seconds=8):
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                m = self.messages.get(timeout=0.4)
                if m.get("type") == type_:
                    return m
            except queue.Empty:
                pass
        return None

    def count(self, type_, seconds):
        n, deadline = 0, time.time() + seconds
        while time.time() < deadline:
            try:
                if self.messages.get(timeout=0.2).get("type") == type_:
                    n += 1
            except queue.Empty:
                pass
        return n

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", type=int, default=8081, help="port of instance A")
    ap.add_argument("--b", type=int, default=8082, help="port of instance B")
    ap.add_argument("--gateway", type=int, default=8080, help="port of the nginx gateway")
    ap.add_argument("--wait", type=int, default=90, help="seconds to wait for each instance to become healthy")
    args = ap.parse_args()
    A, B, G = Api(args.a), Api(args.b), Api(args.gateway)

    print("\n1. The instances")
    for name, api in (("instance A", A), ("instance B", B), ("gateway", G)):
        # right after `docker compose up` an instance can take a while to answer: give it time rather than failing
        check(api.wait_until_healthy(args.wait), f"{name} is healthy")
    if failures:
        sys.exit("The stack is not up (is `docker compose up` running, and are the ports right? see --a/--b/--gateway).")
    _, admin, _ = A.call("POST", "/api/auth/login", body={"phone": "admin", "password": "dev-admin-password"})
    for name, api in (("A", A), ("B", B)):
        status, metric, _ = api.call("GET", "/actuator/metrics/ridehail.realtime.broker.available", admin["accessToken"])
        check(status == 200 and metric["measurements"][0]["value"] == 1.0, f"instance {name} reports it is connected to the message broker")

    print("\n2. People, each connected to a different instance")
    _, c, _ = A.call("POST", "/api/auth/register/customer", body={"name": "Cust", "phone": str(uuid.uuid4()), "password": "password123"})
    _, r, _ = A.call("POST", "/api/auth/register/rider", body={"name": "Rider", "phone": str(uuid.uuid4()), "password": "password123", "vehicleType": "SEDAN"})
    A.call("PUT", "/api/customers/me/payment-method", c["accessToken"], {"token": "tok_ok"})
    rider_ws = Stomp(args.b, r["accessToken"])        # rider's socket lives on B
    cust_ws = Stomp(args.a, c["accessToken"])         # customer's socket lives on A
    check(rider_ws.subscribe("/user/topic/offers") and rider_ws.subscribe("/user/topic/trips"), "rider (on B) subscribed to offers and trips")
    check(cust_ws.subscribe("/user/topic/trips") and cust_ws.subscribe("/user/topic/location"), "customer (on A) subscribed to trips and live location")

    print("\n3. A ride: every push crosses the broker")
    lat = 20.0 + (int(time.time()) % 1000) * 0.05      # a patch of map no earlier run used, so only OUR rider is nearby
    B.call("POST", "/api/riders/me/online", r["accessToken"], {"lat": lat + 0.005, "lon": 77.5})
    _, quote, _ = A.call("POST", "/api/fares/quote", c["accessToken"], {"vehicleType": "SEDAN", "pickupLat": lat, "pickupLon": 77.5, "dropLat": lat - 0.03, "dropLon": 77.53})
    status, trip, _ = A.call("POST", "/api/trips", c["accessToken"], {"quoteId": quote["quoteId"]})
    check(status == 201, "customer booked on instance A")
    offer = rider_ws.wait_for("OFFER_RECEIVED")
    check(offer is not None and offer.get("tripId") == trip["id"], "the offer, created on A, reached the rider's socket on B")
    status, _, _ = B.call("POST", f"/api/riders/me/offers/{offer['offerId']}/accept", r["accessToken"])
    check(status == 204, "rider accepted on instance B")
    accepted = cust_ws.wait_for("TRIP_ACCEPTED")
    check(accepted is not None and accepted.get("riderName") == "Rider", "the acceptance, made on B, reached the customer's socket on A")

    print("\n4. Live location and the limits shared by both instances")
    A.call("PUT", "/api/riders/me/location", r["accessToken"], {"lat": lat + 0.004, "lon": 77.5, "heading": 90.0})
    loc = cust_ws.wait_for("RIDER_LOCATION")
    check(loc is not None and loc.get("heading") == 90.0, "a position reported to A reached the customer on A")
    accepted_updates = 1
    for i in range(3):                                   # three more inside the push interval, alternating instances
        api = B if i % 2 == 0 else A
        accepted_updates += api.call("PUT", "/api/riders/me/location", r["accessToken"], {"lat": lat + 0.003, "lon": 77.5})[0] == 200
    check(cust_ws.count("RIDER_LOCATION", 0.8) == 0, "updates spread over both instances produced ONE push per interval, not one per instance")
    time.sleep(2.1)
    accepted_updates += B.call("PUT", "/api/riders/me/location", r["accessToken"], {"lat": lat + 0.002, "lon": 77.5})[0] == 200
    check(cust_ws.wait_for("RIDER_LOCATION", 4) is not None, "after the interval the next position is pushed")
    refused = []
    for api in (A, B, A, B):
        refused.append(api.call("PUT", "/api/riders/me/location", r["accessToken"], {"lat": 1.0, "lon": 1.0})[0])
    check(accepted_updates == 5 and set(refused) == {429}, f"the rider's allowance of 5 is shared: {accepted_updates} accepted, then 429 on both instances ({refused})")

    print("\n5. Through the gateway (nginx proxies the WebSocket upgrade)")
    try:
        via_gateway = Stomp(args.gateway, c["accessToken"])
        check(via_gateway.subscribe("/user/topic/trips"), "a WebSocket opened through the gateway connects and subscribes")
        via_gateway.close()
    except Exception as e:
        check(False, f"WebSocket through the gateway ({e})")

    rider_ws.close(); cust_ws.close()
    print("\n" + ("ALL CHECKS PASSED" if not failures else f"{len(failures)} CHECK(S) FAILED"))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
