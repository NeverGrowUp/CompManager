package au.com.addstar.comp.voting;

import java.util.Collections;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.intellectualcrafters.plot.object.PlotId;

public class Placement {
	private final Set<PlotId> plots;
	
	public Placement(Set<PlotId> plots) {
		this.plots = plots;
	}
	
	/**
	 * Checks if this placement is definitive, has only
	 * one plot that got this placement
	 * @return True if only one plot exists
	 */
	public boolean isDefinitive() {
		return plots.size() == 1;
	}
	
	/**
	 * Gets the plot that is in this place if this placement
	 * is definitive
	 * @return The plot in this placement
	 * @throws IllegalStateException Thrown if this placement is not definitive
	 * @see #isDefinitive()
	 */
	public PlotId getPlot() throws IllegalStateException {
		Preconditions.checkState(isDefinitive());
		
		return Iterables.getOnlyElement(plots);
	}
	
	/**
	 * Gets the contenders for this placement
	 * @return The contenders
	 */
	public Set<PlotId> getContenders() {
		return Collections.unmodifiableSet(plots);
	}
}
