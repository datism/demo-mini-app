#!/usr/bin/env python3
"""
Test script để test DCSF REST API của Golang MF server.
Mô phỏng Erlang SIP Server gọi vào DCSF.

Usage:
    python3 test_dcsf_api.py --host 127.0.0.1 --port 8080
"""

import requests
import json
import sys
import argparse
import time

def test_allocate_dc(base_url, user_id, call_id):
    """Test POST /allocate_dc"""
    print(f"\n{'='*60}")
    print(f"  POST /allocate_dc - user={user_id} call={call_id}")
    print(f"{'='*60}")

    payload = {"user_id": user_id, "call_id": call_id}
    print(f"Request: {json.dumps(payload, indent=2)}")

    try:
        resp = requests.post(f"{base_url}/allocate_dc", json=payload, timeout=15)
        resp.raise_for_status()
        data = resp.json()
        print(f"Response ({resp.status_code}):")
        print(json.dumps(data, indent=2))
        return data
    except requests.exceptions.RequestException as e:
        print(f"ERROR: {e}")
        return None

def test_link_sessions(base_url, call_id, session_a, session_b):
    """Test POST /link_sessions"""
    print(f"\n{'='*60}")
    print(f"  POST /link_sessions - call={call_id}")
    print(f"{'='*60}")

    payload = {
        "call_id": call_id,
        "session_a": session_a,
        "session_b": session_b,
    }
    print(f"Request: {json.dumps(payload, indent=2)}")

    try:
        resp = requests.post(f"{base_url}/link_sessions", json=payload, timeout=5)
        resp.raise_for_status()
        data = resp.json()
        print(f"Response ({resp.status_code}):")
        print(json.dumps(data, indent=2))
        return data
    except requests.exceptions.RequestException as e:
        print(f"ERROR: {e}")
        return None

def test_list_sessions(base_url):
    """Test GET /sessions"""
    print(f"\n{'='*60}")
    print(f"  GET /sessions")
    print(f"{'='*60}")

    try:
        resp = requests.get(f"{base_url}/sessions", timeout=5)
        resp.raise_for_status()
        data = resp.json()
        print(f"Response ({resp.status_code}):")
        print(json.dumps(data, indent=2))
        return data
    except requests.exceptions.RequestException as e:
        print(f"ERROR: {e}")
        return None

def run_full_scenario(base_url):
    """
    Mô phỏng full scenario:
    1. Erlang nhận SIP INVITE từ A
    2. Erlang allocate DC cho A
    3. Erlang allocate DC cho B
    4. Erlang link 2 sessions
    5. Kiểm tra sessions status
    """
    print("\n" + "="*60)
    print("  IMS DC DCSF API Integration Test")
    print("  Mô phỏng Erlang SIP Server -> Golang MF")
    print("="*60)

    call_id = f"call-test-{int(time.time())}"

    # Step 1: Allocate DC for User A
    print("\n[Step 1] Erlang allocates DC for User A...")
    resp_a = test_allocate_dc(base_url, "userA", call_id)
    if not resp_a:
        print("FAIL: Cannot allocate for User A")
        return False

    session_a = resp_a["session_id"]
    print(f"\n[OK] User A session: {session_a}")
    print(f"     MF endpoint: {resp_a['ip']}:{resp_a['port']}")
    print(f"     ICE ufrag:   {resp_a['ice_ufrag']}")
    print(f"     Fingerprint: {resp_a['dtls_fingerprint'][:40]}...")

    # Step 2: Allocate DC for User B
    print("\n[Step 2] Erlang allocates DC for User B...")
    resp_b = test_allocate_dc(base_url, "userB", call_id)
    if not resp_b:
        print("FAIL: Cannot allocate for User B")
        return False

    session_b = resp_b["session_id"]
    print(f"\n[OK] User B session: {session_b}")
    print(f"     MF endpoint: {resp_b['ip']}:{resp_b['port']}")

    # Step 3: Link sessions A <-> B
    print("\n[Step 3] Erlang links sessions A <-> B for P2A2P...")
    resp_link = test_link_sessions(base_url, call_id, session_a, session_b)
    if not resp_link:
        print("FAIL: Cannot link sessions")
        return False

    print(f"\n[OK] Sessions linked for call {call_id}")

    # Step 4: Check sessions
    print("\n[Step 4] Checking active sessions...")
    test_list_sessions(base_url)

    print("\n" + "="*60)
    print("  ✅ ALL TESTS PASSED!")
    print(f"  Call ID: {call_id}")
    print(f"  Session A: {session_a}")
    print(f"  Session B: {session_b}")
    print("="*60)
    return True

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="DCSF API Test")
    parser.add_argument("--host", default="127.0.0.1", help="MF server host")
    parser.add_argument("--port", default=8080, type=int, help="MF server port")
    args = parser.parse_args()

    base_url = f"http://{args.host}:{args.port}"
    print(f"Testing DCSF API at: {base_url}")

    ok = run_full_scenario(base_url)
    sys.exit(0 if ok else 1)
