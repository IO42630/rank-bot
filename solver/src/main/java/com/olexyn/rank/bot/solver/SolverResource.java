package com.olexyn.rank.bot.solver;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/solve")
@Consumes(MediaType.TEXT_PLAIN)
@Produces(MediaType.TEXT_PLAIN)
public class SolverResource {
	@Inject
	LocalAgentRunner runner;

	@POST
	public String solve(String text) {
		return runner.solve(text);
	}
}