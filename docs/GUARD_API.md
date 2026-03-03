# GUARD_API: strict public API reference

Version: `v1 (draft)`
Repository: `https://github.com/algebrain/lcmm-guard`

This document is the strict interface contract for `lcmm-guard`.
Use it as the source of truth for function signatures, arguments, return shapes, and decision semantics.

## 1. Conventions

1. Time values (`:now`, `:ts`, `expires-at-sec`) are epoch-seconds.
2. IP handling in guard-path is literal-only (no DNS hostname resolution).
3. Return maps are data contracts: callers should branch on keys, not on internal implementation details.

## 2. Core API

## 2.1 `lcmm-guard.core/make-guard`

Signature:

```clojure
(make-guard {:ip-config ...
             :ban-store ...
             :rate-limiter ...
             :detector ...
             :mode-policy ...})
```

Required args:
1. `:ip-config` map (`:trust-xff?`, `:trusted-proxies`).
2. `:ban-store` instance from `lcmm-guard.ban-store/make-ban-store`.
3. `:rate-limiter` instance from `lcmm-guard.rate-limiter/make-rate-limiter`.
4. `:detector` instance from `lcmm-guard.detector/make-detector`.
5. `:mode-policy` map with `:mode` = `:fail-open | :fail-closed`.

Returns:
1. Guard instance map (opaque for callers; pass into `evaluate-request!` / `unban-ip!`).

Notes:
1. `:ip-config` is normalized internally via `prepare-ip-config`.

## 2.2 `lcmm-guard.core/evaluate-request!`

Signature:

```clojure
(evaluate-request! guard-instance
                   {:request ring-request
                    :now epoch-seconds
                    :kind :validation-failed|:auth-failed|:suspicious|nil
                    :endpoint string?
                    :code keyword?|string?|nil
                    :correlation-id string?|nil})
```

Required args:
1. `guard-instance` from `make-guard`.
2. `:request` (Ring-like map, must include `:remote-addr`; headers optional).
3. `:now` in seconds.

Optional args:
1. `:kind` (nil means no detector event recording).
2. `:endpoint` (recommended when `:kind` is provided).
3. `:code` (optional domain reason code).
4. `:correlation-id` (optional tracing id).

Return shape:

```clojure
{:action :allow|:rate-limited|:banned|:degraded-allow|:degraded-block
 :ip "canonical-ip"|nil
 :events [{:event/kind keyword
           :event/ts long-ms
           :event/payload map} ...]}
```

Action semantics:
1. `:allow` — request may continue to business handler.
2. `:rate-limited` — request should be rejected (`429` in typical policy).
3. `:banned` — request should be rejected (`429` or `403` by app policy).
4. `:degraded-allow` — protection degraded, allow by fail-open policy.
5. `:degraded-block` — protection degraded, block by fail-closed policy.

Event note:
1. Resolver warning `:proxy-config-empty` may surface in `evaluate-request!` result as event `:guard/proxy-misconfig` in `:events`.

Minimal example:

```clojure
(guard/evaluate-request! g
                         {:request {:remote-addr "203.0.113.10" :headers {}}
                          :now 1700000000
                          :correlation-id "req-1"})
```

## 2.3 `lcmm-guard.core/unban-ip!`

Signature:

```clojure
(unban-ip! guard-instance
           {:ip "canonical-or-literal-ip"
            :reason keyword?|string?|nil
            :now epoch-seconds|nil
            :correlation-id string?|nil})
```

Required args:
1. `guard-instance` from `make-guard`.
2. `:ip` target IP to unban.

Optional args:
1. `:reason` (for logging/audit).
2. `:now` (for trace payload only).
3. `:correlation-id`.

Return shape (success):

```clojure
{:ok? true
 :ip "..."
 :events [{:event/kind :guard/unbanned ...}]}
```

Return shape (degraded path):

```clojure
{:ok? false
 :ip "..."
 :action :degraded-allow|:degraded-block
 :events [{:event/kind :guard/degraded ...}]}
```

Example:

```clojure
(guard/unban-ip! g {:ip "203.0.113.10"
                    :reason :manual
                    :correlation-id "admin-42"})
```

## 3. IP resolver API

## 3.1 `lcmm-guard.ip-resolver/prepare-ip-config`

Signature:

```clojure
(prepare-ip-config {:trust-xff? bool
                    :trusted-proxies coll-of-ip-literals})
```

Returns:
1. Normalized config map with canonical trusted proxy set.

Notes:
1. Invalid trusted proxy entries are dropped.

## 3.2 `lcmm-guard.ip-resolver/resolve-client-ip`

Signature:

```clojure
(resolve-client-ip ip-config ring-request)
```

Required args:
1. `ip-config` (prefer output of `prepare-ip-config`).
2. `ring-request` with `:remote-addr` and optional header `x-forwarded-for`.

Return shape:

```clojure
{:ip "canonical-ip"|nil
 :source :remote-addr|:xff|:unresolved
 :warnings [:proxy-config-empty ...]}
```

Warning semantics:
1. `:proxy-config-empty` appears when `trust-xff?` is true and trusted proxy set is empty.
2. In guard flow, this warning is represented as `:guard/proxy-misconfig` event in `evaluate-request!` output.

Selection semantics:
1. XFF chain is used only when request comes from trusted proxy.
2. Client IP is selected right-to-left by dropping trusted hops.
3. If nothing valid remains, fallback is canonical `:remote-addr`, otherwise unresolved.

## 4. Ban store API

## 4.1 `lcmm-guard.ban-store/make-ban-store`

Signature:

```clojure
(make-ban-store {:ttl-store ttl-store
                 :allow-list #{...}
                 :default-ban-ttl-sec 900
                 :flush-on-ban? false})
```

Required args:
1. `:ttl-store` implementing `TtlStore` protocol.

Optional args + defaults:
1. `:allow-list` default `{}`.
2. `:default-ban-ttl-sec` default `900`.
3. `:flush-on-ban?` default `false`.

## 4.2 `ban!`

Signature:

```clojure
(ban! ban-store ip reason now-sec)
(ban! ban-store ip reason now-sec {:ttl-sec n})
```

Return (allow-listed):

```clojure
{:banned? false :allow-listed? true :ip "..."}
```

Return (banned):

```clojure
{:banned? true
 :allow-listed? false
 :ban {:ip "..." :reason ... :created-at ... :until ...}}
```

## 4.3 `banned?`

Signature:

```clojure
(banned? ban-store ip now-sec)
```

Return:

```clojure
{:banned? true|false
 :ban map|nil}
```

## 4.4 `unban!`

Signature:

```clojure
(unban! ban-store ip)
```

Return:

```clojure
{:unbanned? true :ip "..."}
```

## 5. Rate limiter API

## 5.1 `make-rate-limiter`

Signature:

```clojure
(make-rate-limiter {:counter-store counter-store
                    :limit 60
                    :window-sec 60})
```

Required args:
1. `:counter-store` implementing `CounterStore`.

Optional args + defaults:
1. `:limit` default `60`.
2. `:window-sec` default `60`.

## 5.2 `allow?`

Signature:

```clojure
(allow? limiter {:key any-hashable
                 :now epoch-seconds})
```

Return:

```clojure
{:allowed? true|false
 :limit long
 :remaining long
 :reset-at epoch-seconds}
```

## 6. Detector API

## 6.1 `make-detector`

Signature:

```clojure
(make-detector {:counter-store counter-store
                :thresholds {:validation-failed 20 :auth-failed 20 :suspicious 20}
                :window-sec 300
                :bucket-sec 10})
```

Required args:
1. `:counter-store` implementing `CounterStore`.

Optional args + defaults:
1. `:thresholds` default `{validation/auth/suspicious -> 20}`.
2. `:window-sec` default `300`.
3. `:bucket-sec` default `10`.

## 6.2 `record-event!`

Signature:

```clojure
(record-event! detector
               {:kind :validation-failed|:auth-failed|:suspicious
                :ip "canonical-ip"
                :endpoint string|nil
                :code any|nil
                :ts epoch-seconds})
```

Return:

```clojure
{:kind keyword
 :count long
 :threshold long
 :window-sec long
 :triggered? boolean}
```

## 7. Mode policy API

## 7.1 `lcmm-guard.mode-policy/with-mode`

Signature:

```clojure
(with-mode {:mode :fail-open|:fail-closed} thunk)
```

Return (success):

```clojure
{:ok? true :value any}
```

Return (handled exception):

```clojure
{:ok? false :action :degraded-allow|:degraded-block :error ex}
```

Note:
1. Handles `Exception`; fatal `Error` is not swallowed.

## 8. Backend protocol reference

## 8.1 `CounterStore`

```clojure
(incr-bucket! this k bucket)        ; => long
(buckets-snapshot this k)           ; => {bucket count}
(prune-before-bucket! this k min-bucket) ; => nil (return value ignored)
```

Semantics:
1. `incr-bucket!` must be atomic.
2. `buckets-snapshot` should return buckets for the given key only.
3. `prune-before-bucket!` removes buckets `< min-bucket` for key.

## 8.2 `TtlStore`

```clojure
(put-ttl! this k value expires-at-sec) ; => value
(get-live this k now-sec)              ; => value|nil
(delete-key! this k)                   ; => nil (return value ignored)
```

## 8.3 `DurableStore` (optional)

```clojure
(flush! this)
(shutdown! this)
```

Used by in-memory persistence and optional durability hooks.

## 9. Quick integration checklist

1. Build guard with `make-guard`.
2. Call `evaluate-request!` before business handler.
3. Map `:action` to HTTP response at app-layer.
4. Forward `:events` to logging/monitoring.
5. Use `unban-ip!` for explicit operational unban flow.
