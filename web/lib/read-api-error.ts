export async function readApiError(response: Response): Promise<string> {
  try {
    const error = (await response.json()) as { message?: string };
    return error.message || "The request was rejected.";
  } catch {
    return `Request failed (${response.status}).`;
  }
}
