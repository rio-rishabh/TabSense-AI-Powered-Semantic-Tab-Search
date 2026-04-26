export interface TabPayload {
  url: string;
  title: string;
  tabId: string;
}

export interface Citation {
  tabId: string;
  url: string;
  snippet: string;
}

export interface SearchResultPayload {
  answer: string;
  citations: Citation[];
}

const SYNC_MUTATION = `
  mutation SyncTabs($tabs: [TabInput!]!) {
    syncTabs(tabs: $tabs)
  }
`;

const SEARCH_QUERY = `
  query Search($query: String!) {
    search(query: $query) {
      answer
      citations {
        tabId
        url
        snippet
      }
    }
  }
`;

function graphqlUrl(base: string): string {
  const trimmed = base.replace(/\/$/, "");
  return `${trimmed}/graphql`;
}

export async function syncTabsRequest(
  baseUrl: string,
  tabs: TabPayload[]
): Promise<boolean> {
  const res = await fetch(graphqlUrl(baseUrl), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      query: SYNC_MUTATION,
      variables: { tabs },
    }),
  });
  const body = (await res.json()) as {
    data?: { syncTabs: boolean };
    errors?: { message: string }[];
  };
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${res.statusText}`);
  }
  if (body.errors?.length) {
    throw new Error(body.errors.map((e) => e.message).join("; "));
  }
  if (body.data?.syncTabs !== true) {
    throw new Error("Sync did not complete successfully.");
  }
  return true;
}

export async function searchRequest(
  baseUrl: string,
  query: string
): Promise<SearchResultPayload> {
  const res = await fetch(graphqlUrl(baseUrl), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      query: SEARCH_QUERY,
      variables: { query },
    }),
  });
  const body = (await res.json()) as {
    data?: { search: SearchResultPayload };
    errors?: { message: string }[];
  };
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${res.statusText}`);
  }
  if (body.errors?.length) {
    throw new Error(body.errors.map((e) => e.message).join("; "));
  }
  const search = body.data?.search;
  if (!search) {
    throw new Error("Empty search response.");
  }
  return search;
}
