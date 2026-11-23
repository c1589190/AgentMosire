// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.agentlib.proc;

/** 子进程 spawn / adopt 失败的统一异常。 */
public final class SubprocessException extends RuntimeException {

  public SubprocessException(String message) {
    super(message);
  }

  public SubprocessException(String message, Throwable cause) {
    super(message, cause);
  }
}
