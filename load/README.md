# Load

`load.mjs` is a closed-loop load generator for the MCP payment path. It is Node, not k6: k6 is not
installed on the machine this was built on, and a script that runs is worth more than a tool that
would have to be installed to be honest about.

```
# against the compose stack, through the edge
GATEWAY_BASE_URL=http://localhost:18088 AGENT_API_KEY=apg_... node load/load.mjs

# tune it
REQUESTS=300 CONCURRENCY=10 node load/load.mjs
```

Each request is a real `request_payment` with a unique idempotency key, so a run exercises policy
evaluation, the Postgres transaction, the outbox enqueue and the MCP response. The run exits non-zero
unless every request was approved.

What it does not do: it does not measure the provider call or the webhook path (those happen
asynchronously in the outbox worker), and it is not a soak test. Numbers from a laptop with a shared
Postgres container are a demonstration of behaviour under concurrency, not a capacity claim.
