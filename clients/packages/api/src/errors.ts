/** The server answered with an error (or could not be reached: status 0). The message is safe to show to a person. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    /** From the Retry-After header on a 429: how long to back off, in seconds. */
    readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "ApiError";
  }

  get isNetwork(): boolean {
    return this.status === 0;
  }
  get isUnauthorized(): boolean {
    return this.status === 401;
  }
  get isRateLimited(): boolean {
    return this.status === 429;
  }
}

/** The server answered 2xx but the body is not what this client expects: the backend and the app have drifted apart. */
export class ContractError extends Error {
  constructor(
    readonly path: string,
    readonly detail: string,
  ) {
    super(`Unexpected response from ${path}: ${detail}`);
    this.name = "ContractError";
  }
}
