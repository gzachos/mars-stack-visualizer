package mars.tools;

import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.util.Observable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;
import mars.mips.hardware.RegisterAccessNotice;
import mars.mips.hardware.RegisterFile;

@SuppressWarnings("serial")
public class StackVisualizer extends AbstractMarsToolAndApplication {

	private static String name = "Stack Visualizer";
	private static String version = "Version 0.1 (George Z. Zachos, Petros Manousis)";
	private static String heading = "Visualizing stack modification operations";

	private static int spRegNumber = RegisterFile.STACK_POINTER_REGISTER;

	protected StackVisualizer(String title, String heading) {
		super(title, heading);
	}

	public StackVisualizer() {
		super(StackVisualizer.name + ", " + StackVisualizer.version, StackVisualizer.heading);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	protected JComponent buildMainDisplayArea() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setPreferredSize(new Dimension(600, 650));
		panel.add(new JLabel("Test Label Content"));
		return panel;
	}

	@Override
	protected void addAsObserver() {
		addAsObserver(Memory.stackLimitAddress, Memory.stackBaseAddress);
		addAsObserver(RegisterFile.getRegisters()[spRegNumber]);
	}

	@Override
	protected void processMIPSUpdate(Observable resource, AccessNotice notice) {

		if (notice instanceof MemoryAccessNotice) {
			MemoryAccessNotice m = (MemoryAccessNotice) notice;
			System.out.println("MemoryAccessNotice (" + 
					((m.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
					+ hex(m.getAddress()) + " value: " + m.getValue());
		}
		else if (notice instanceof RegisterAccessNotice) {
			RegisterAccessNotice r = (RegisterAccessNotice) notice;
			if (r.getAccessType() == AccessNotice.READ)
				return;
			System.out.println("RegisterAccessNotice (W): " + r.getRegisterName()
					+ " value: " + hex(getSpValue()));
		}
	}

	private String hex(int decimalValue) {
		return Integer.toHexString(decimalValue);
	}

	private int getSpValue() {
		return RegisterFile.getValue(spRegNumber);
	}

}
