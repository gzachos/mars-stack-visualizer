package mars.tools;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Observable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import mars.Globals;
import mars.ProgramStatement;
import mars.assembler.Symbol;
import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;
import mars.mips.hardware.Register;
import mars.mips.hardware.RegisterAccessNotice;
import mars.mips.hardware.RegisterFile;
import mars.mips.instructions.Instruction;
import mars.simulator.BackStepper;
import mars.venus.VenusUI;

@SuppressWarnings({ "serial", "deprecation" })
public class StackVisualizer extends AbstractMarsToolAndApplication {

	private static String name        = "Stack Visualizer";
	private static String versionID   = "0.2";
	private static String version     = "Version " + versionID + " (George Z. Zachos, Petros Manousis)";
	private static String heading     = "Visualizing Stack Modification Operations";
	private static String releaseDate = "28-Sep-2018";

	private static boolean displayDataPerByte = false;

	/*
	 * Memory.stackBaseAddress:  word-aligned
	 * Memory.stackLimitAddress: word-aligned
	 * Max stack address value:  Memory.stackBaseAddress + (WORD_LENGTH_BYTES-1)
	 * Min stack address value:  Memory.stackLimitAddress
	 *
	 * Stack grows towards lower addresses: .stackBaseAddress > .stackLimitAddress
	 * Word-length operations can take place in both .stackBaseAddress and .stackLimitAddress
	 */
	private static final int SP_REG_NUMBER = RegisterFile.STACK_POINTER_REGISTER;
	private static final int SP_INIT_ADDR  = Memory.stackPointer;
	private static Memory    memInstance   = Memory.getInstance();
	private static boolean   endianness    = memInstance.getByteOrder();
	private static VenusUI   marsGui       = Globals.getGui();
	private static final boolean LITTLE_ENDIAN = Memory.LITTLE_ENDIAN;

	private static final int  WORD_LENGTH_BYTES         = Memory.WORD_LENGTH_BYTES;
	private static final int  WORD_LENGTH_BITS          = WORD_LENGTH_BYTES * 8;
	// data[][] related fields
	private static final int  NUMBER_OF_COLUMNS         = WORD_LENGTH_BYTES + 2; // +1 for address
	                                                                             // +1 for register name stored
	private static final int  ADDRESS_COLUMN            = 0; // Should always be in first column.
	private static final int  FIRST_BYTE_COLUMN         = 1; // Should always be in second column.
	private static final int  LAST_BYTE_COLUMN          = FIRST_BYTE_COLUMN + WORD_LENGTH_BYTES - 1;
	private static final int  STORED_REGISTER_COLUMN    = LAST_BYTE_COLUMN + 1;
	private static final int  I_RS_OPERAND_LIST_INDEX   = 0; // I-format RS (source register) index
	private static final int  J_ADDR_OPERAND_LIST_INDEX = 0; // J-format Address index
	private static final int  R_RS_OPERAND_LIST_INDEX   = 0; // R-format RS (source register) index
	private static final int  INITIAL_ROW_COUNT         = 24;
	private static final int  INITIAL_MAX_SP_VALUE      = SP_INIT_ADDR + 3;
	private static final int  maxSpValue                = INITIAL_MAX_SP_VALUE;
	private static int        maxSpValueWordAligned     = SP_INIT_ADDR;
	private static String     regNameToBeStoredInStack  = null;

	// GUI-Related fields
	private static String[]   colNames = {"Address", "+3", "+2", "+1", "+0", "Stored Reg"};
	private JTable            table;
	private JPanel            panel;
	private JScrollPane       scrollPane;
	private int               spDataIndex = 0;
	private DefaultTableModel tableModel = new DefaultTableModel();
	private static JTextField spField;

	private static ArrayList<?> textSymbols = null; // TODO verify generics
	private static ArrayList<Integer> jumpAddresses = new ArrayList<Integer>();
	private static boolean disabledBackStep = false;

	private static boolean debug = false, printMemContents = false, debugBackStepper = false,
			debugTextSymbols = false;

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
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		panel = new JPanel(new GridBagLayout());
		panel.setPreferredSize(new Dimension(600, 650));
		spField = new JTextField("Stack Pointer Value", 10);
		spField.setEditable(false);
		panel.add(spField, c);
		for (String s : colNames)
			tableModel.addColumn(s);
		table = new JTable(tableModel);
		table.setEnabled(false);
		table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
			@Override
		    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
		        final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
		        Color color = Color.WHITE;
		        if (row == spDataIndex) {
		        	color = Color.YELLOW;
		        }
		        if (row > spDataIndex) {
		        	color = Color.LIGHT_GRAY;
		        }
		        c.setBackground(color);
		        return c;
		    }
		});
		scrollPane = new JScrollPane(table);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		// TODO: disable re-ordering of columns by cursor dragging
		// TODO: autoscroll around stack pointer?
		scrollPane.setVisible(true);
		panel.add(scrollPane, c);
		table.setFillsViewportHeight(true);

		return panel;
	}

	@Override
	protected void initializePreGUI() {

		// TODO: disable actions performed when Tool not connected!
		marsGui.getRunAssembleItem().addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (debugBackStepper) {
					System.out.flush();
					System.err.println("RunAssemble");
					System.err.flush();
				}
				textSymbols = null;        // Clear labels
				jumpAddresses.clear();     // Clear jump addresses

				/* Reset the column holding the register name whose contents
				 * were stored in the corresponding memory address.
				 */
				for (int i = 0; i < tableModel.getRowCount(); i++)
					tableModel.setValueAt("", i, STORED_REGISTER_COLUMN);

				runButtonsSetEnabled(true);

				getStackData();
				spDataIndex = getTableIndex(getSpValue());
				table.repaint();
			}
		});

		marsGui.getAssembleButton().addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (debugBackStepper) {
					System.out.flush();
					System.err.println("Assemble");
					System.err.flush();
				}
				textSymbols = null;        // Clear labels
				jumpAddresses.clear();     // Clear jump addresses

				/* Reset the column holding the register name whose contents
				 * were stored in the corresponding memory address.
				 */
				for (int i = 0; i < tableModel.getRowCount(); i++)
					tableModel.setValueAt("", i, STORED_REGISTER_COLUMN);

				runButtonsSetEnabled(true);

				getStackData();
				spDataIndex = getTableIndex(getSpValue());
				table.repaint();
			}
		});

	}

	@Override
	protected void initializePostGUI() {

		connectButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (connectButton.isConnected()) {
					restoreBackStepper(); // TODO We really need this?
				} else {
					/*
					 * User program should be recompiled (and executed) after
					 * StackVisualizer is launched. This is required for
					 * coherently storing the subroutine call stack.
					 */
					runButtonsSetEnabled(false);
//					marsGui.getRunBackstepAction().isEnabled(); // TODO disable?
					disableBackStepper();
					getStackData();
					spDataIndex = getTableIndex(getSpValue());
					table.repaint();

					String msg = "Back Stepping has been disabled.\n"
							+ "Already running programs should be assembled again.";
					showMessageWindow(msg);
				}
			}
		});

		for (int i = 0; i < INITIAL_ROW_COUNT; i++)
			tableModel.addRow(new Object[NUMBER_OF_COLUMNS]);
		getStackData();
		spDataIndex = getTableIndex(getSpValue());
		table.repaint(); // Maybe we can remove this
	}

	/*
	 * getStackData() fires a MemoryAccessNotice every time it reads from memory.
	 * For this reason it should not be called in a code block handling a
	 * MemoryAccessNotice of AccessNotice.READ type as it will lead in infinite
	 * recursive calls of itself.
	 */
	protected void getStackData() {
		int col;

		if (printMemContents)
			System.out.println("getStackData start");
		/*
		 * MARS supports three memory configurations. Only in the default configuration
		 * the initial stack pointer value does NOT point to the highest address of the
		 * stack. Nevertheless, in all three configurations, the initial address pointed
		 * by $sp is a valid address whose contents can be written.
		 *
		 * TODO support visualization of addresses higher than initial $sp value
		 *
		 * Initial value of spAddr is 0x7FFFEFFC = 2147479548 (Default MIPS memory configuration).
		 * The first 4 bytes (1 word) to be displayed are:
		 * 0x7FFFEFFF, 0x7FFFEFFE, 0x7FFFEFFD, 0x7FFFEFFC or in decimal value:
		 * 2147479551, 2147479550, 2147479549, 2147479548.
		 */
		for (int row = 0, addr = maxSpValue; row < tableModel.getRowCount(); row++) {
			tableModel.setValueAt("0x" + hex(addr-3), row, ADDRESS_COLUMN);
			try {
				// TODO Allow 'whole word' or 'per byte' data display.
				for (int j = FIRST_BYTE_COLUMN; j <= LAST_BYTE_COLUMN; j++) {
					/*
					 * Endianness determines whether byte position in value and
					 * byte position in memory match.
					 */
					col = (endianness == LITTLE_ENDIAN) ? j : (LAST_BYTE_COLUMN-j) + FIRST_BYTE_COLUMN;
					if (displayDataPerByte)
						System.out.println("(" + row + "," + col + ") - " + addr +": " + hex(memInstance.getByte(addr)));
					tableModel.setValueAt(hex(memInstance.getByte(addr--)), row, col);
				}
				if (printMemContents) {
					System.out.print(tableModel.getValueAt(row, 0) + ": ");
					for (int i = FIRST_BYTE_COLUMN; i <= LAST_BYTE_COLUMN; i++)
						System.out.print(tableModel.getValueAt(row, i) + (i == LAST_BYTE_COLUMN ? "" : ","));
					System.out.println(" (" + tableModel.getValueAt(row, STORED_REGISTER_COLUMN) + ")");
				}
			} catch (AddressErrorException aee) {
				aee.printStackTrace();
			}
		}

		if (printMemContents)
			System.out.println("getStackData end\n");
	}

	@Override
	protected void addAsObserver() {
		// To observe stack segment, actual parameters should be
		// reversed due to higher to lower address stack growth.
		addAsObserver(Memory.stackLimitAddress, Memory.stackBaseAddress);
		/*
		 * TODO: verify that Memory.stackBaseAdress+1 to Memory.stackBaseAdress+3
		 * can be observed during half word- or byte-length operations.
		 * Update check in getTableIndex() too if needed.
		 */
		addAsObserver(RegisterFile.getRegisters()[SP_REG_NUMBER]);
		addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
	}

	@Override
	protected void deleteAsObserver() {
		super.deleteAsObserver(); // Stop observing memory (default)
		deleteAsObserver(RegisterFile.getRegisters()[SP_REG_NUMBER]); // Stop observing $sp
	}

	@Override
	protected void processMIPSUpdate(Observable resource, AccessNotice notice) {

//		System.out.println(notice.accessIsFromMIPS() +" " + notice.accessIsFromGUI() + " " + notice);

		if (textSymbols == null) { // TODO retrieve symbols after assemble. verify it works
			// TODO: check for Globals.program == null?
			textSymbols = Globals.program.getLocalSymbolTable().getTextSymbols();
			if (debugTextSymbols) {
				System.out.println(textSymbols.toString() + " " + textSymbols.size());
				for (int i = 0; i < textSymbols.size(); i++) {
					Symbol s = (Symbol) textSymbols.get(i);
					System.out.println(s.getName() + " - " + s.getAddress());
				}
			}
		}

		if (!notice.accessIsFromMIPS())
			return;

		disableBackStepper();

		if (notice instanceof MemoryAccessNotice) {
			MemoryAccessNotice m = (MemoryAccessNotice) notice;
			if (Memory.inTextSegment(m.getAddress()))
				processTextMemoryUpdate(m);
			else
				processStackMemoryUpdate(m);
		}
		else if (notice instanceof RegisterAccessNotice) {
			RegisterAccessNotice r = (RegisterAccessNotice) notice;
			processRegisterAccessNotice(r);
		}
	}

	private void processRegisterAccessNotice(RegisterAccessNotice notice) {
		// Currently only $sp is observed
		// TODO: What about observing frame pointer?
		// TODO: What about copying $sp and $fp into other registers?
		if (notice.getAccessType() == AccessNotice.READ)
			return;

		if (debug)
			System.out.println("\nRegisterAccessNotice (W): " + notice.getRegisterName() + " value: " + getSpValue());

		if (notice.getRegisterName().equals("$sp")) {
			spDataIndex = getTableIndex(getSpValue());
//			 System.out.println("SP value: 0x" + hex(getSpValue()) + " - tableIndex: " + spDataIndex);
			// Add more rows if we are reaching current row count
			if (spDataIndex + 5 > tableModel.getRowCount()) {
				for (int i = 0; i < 5; i++)
					tableModel.addRow(new Object[NUMBER_OF_COLUMNS]);
				getStackData();
			}
			table.repaint(); // Required for coloring $sp position during popping.
		}
	}

	private void processStackMemoryUpdate(MemoryAccessNotice notice) {
		String regName;

		if (notice.getAccessType() == AccessNotice.READ)
			return;

		if (regNameToBeStoredInStack != null) {
			regName = regNameToBeStoredInStack;
			regNameToBeStoredInStack = null;
		} else {
			regName = "";
		}

		if (debug) {
			System.out.println("\nStackAccessNotice (" +
					((notice.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
					+ notice.getAddress() + " value: " + notice.getValue() +
					" (stored: " + regName + ")");
		}

		int row = getTableIndex(notice.getAddress());

		if (debug)
			System.out.println("Addr: 0x" + hex(notice.getAddress()) + " - tableIndex: " + row + " (" + regName + ")");

		tableModel.setValueAt(regName, row, STORED_REGISTER_COLUMN);
		getStackData();
		table.repaint();
	}

	private int getTableIndex(int memAddress) {
		if (memAddress > Memory.stackBaseAddress || memAddress < Memory.stackLimitAddress) {
			System.err.println("getTableIndex() only works for stack segment addresses");
			return -1;
		}
		return (maxSpValueWordAligned - alignToCurrentWordBoundary(memAddress)) / WORD_LENGTH_BYTES;
	}

	private void processTextMemoryUpdate(MemoryAccessNotice notice) {
		if (notice.getAccessType() == AccessNotice.WRITE)
			return;

		if (debug) {
			System.out.println("\nTextAccessNotice (R): " + notice.getAddress()
					+ " value: " + notice.getValue() /*+ " = "*/);
		}
//		printBin(notice.getValue());

		try {
			ProgramStatement stmnt =  memInstance.getStatementNoNotify(notice.getAddress());
			Instruction instr = stmnt.getInstruction();
			String instrName = instr.getName();
			int[] operands;

			if (isStoreInstruction(instrName)) {
				if (debug)
					System.out.println("Statement TBE: " + stmnt.getPrintableBasicAssemblyStatement());
				operands = stmnt.getOperands();
//				for (int i = 0; i < operands.length; i++)
//					System.out.print(operands[i] + " ");
//				System.out.println();
				regNameToBeStoredInStack = RegisterFile.getRegisters()[operands[I_RS_OPERAND_LIST_INDEX]].getName();
			}
			else if (isJumpInstruction(instrName) || isJumpAndLinkInstruction(instrName)) {
				int targetAdrress = stmnt.getOperand(J_ADDR_OPERAND_LIST_INDEX) * 4;
				String targetLabel = addrToTextSymbol(targetAdrress);
				if (isJumpAndLinkInstruction(instrName))
					jumpAddresses.add(stmnt.getAddress());
				if (targetLabel != null) {
					if (debug)
						System.out.print("Jumping to: " + targetLabel);
					if (debug && isJumpAndLinkInstruction(instrName))
						System.out.println(" (" + (jumpAddresses.size()) + ")");
				}
			}
			else if (isJumpRegInstruction(instrName)) {
				int targetRegister = stmnt.getOperand(R_RS_OPERAND_LIST_INDEX);
				Register reg = RegisterFile.getRegisters()[targetRegister];
				int targetAddress = reg.getValue();
				// targetAddress-4 is needed as PC+4 is stored in $ra when jal is executed.
				// TODO: Verify it always works
				ProgramStatement callerStatement =  memInstance.getStatementNoNotify(targetAddress-4);
				if (debug) {
					System.out.println("Returning from: " + addrToTextSymbol(callerStatement.getOperand(0)*4) +
							" (" +jumpAddresses.size() + ") to line: " + callerStatement.getSourceLine());
				}
//				System.out.println(jumpAddresses.get(jumpAddresses.size()-1) + " == " + callerStatement.getAddress());
//				for (int i = 0; i< jumpAddresses.size(); i++)
//					System.out.println((i+1) + ": " + jumpAddresses.get(i));

				try {
					if (jumpAddresses.remove(jumpAddresses.size()-1) != callerStatement.getAddress())
						System.out.println("Mismatching return address");
				} catch (IndexOutOfBoundsException e) {
					// Exception is thrown whenever function calling instructions are back-stepped (undone)
					// and again executed. Undoing the last step is not supported!
					e.printStackTrace();
				}
			}
		} catch (AddressErrorException e) {
			e.printStackTrace();
		}
	}

	private boolean isStoreInstruction(String instrName) {
		if (instrName.equals("sw") || instrName.equals("sh") ||
				instrName.equals("sc") || instrName.equals("sb"))
			return true;
		return false;
	}

	private boolean isJumpInstruction(String instrName) {
		return (instrName.equals("j"));
	}

	private boolean isJumpAndLinkInstruction(String instrName) {
		return (instrName.equals("jal"));
	}

	private boolean isJumpRegInstruction(String instrName) {
		return (instrName.equals("jr"));
	}

	private String addrToTextSymbol(int address) {
		if (textSymbols == null) {
			// In this case we can retrieve text symbols here and not return null.
			// textSymbols = Globals.program.getLocalSymbolTable().getTextSymbols();
			return null;
		}

		if (!Memory.inTextSegment(address)) {
			System.err.println("addrToTextSymbol() only works for text segment addresses");
			return null;
		}

		for (int i = 0; i < textSymbols.size(); i++) {
			Symbol s = (Symbol) textSymbols.get(i);
			if (s.getAddress() == address)
				return s.getName();
		}
		System.err.println("addrToTextSymbol(): Error translating address to label");
		return null;
	}

	private String hex(int decimalValue) {
		return Integer.toHexString(decimalValue);
	}

	private int getSpValue() {
		return RegisterFile.getValue(SP_REG_NUMBER);
	}

	/*
	 * The ignoreObserving flag is required for disabling
	 * BackStepper when the Connect button is pressed.
	 * (The tool is not yet registered as observing)
	 */
	private void disableBackStepper() {
		if (Globals.program == null)
			return;
		BackStepper bs = Globals.program.getBackStepper();
		if (bs == null)
			return;
		if (bs.enabled()) {
			if (debugBackStepper)
				System.err.println("Disabled BackStepper");
			bs.setEnabled(false);
			disabledBackStep = true;
		}
	}

	private void restoreBackStepper() {
		if (disabledBackStep) {
			disabledBackStep = false;
			if (Globals.program == null)
				return;
			BackStepper bs = Globals.program.getBackStepper();
			if (bs == null)
				return;
			if (!bs.enabled()) {
				if (debugBackStepper)
					System.err.println("Enabled BackStepper");
				bs.setEnabled(true);
			}
		}
	}

	private void runButtonsSetEnabled(boolean state) {
		marsGui.getRunGoAction().setEnabled(state);
		marsGui.getRunStepAction().setEnabled(state);
	}

	/*
	 *  TODO disable BackStepper when menu items are pressed
	 *       (currently only toolbar buttons are supported)
	 *       (Live edit: menu items are supported but refactoring
	 *       is required)
	 */

//	@Override
//	protected void performSpecialClosingDuties() {
//		disabledBackStep = false;
//	}

	/**
	 * Utility method to align given address to current full word boundary,
	 * if not already aligned.
	 *
	 * @param address
	 *            a memory address (any int value is potentially valid)
	 * @return address aligned to current word boundary (divisible by 4)
	 */
	private int alignToCurrentWordBoundary(int address) {
		if (Memory.wordAligned(address))
			return address;
		return (Memory.alignToWordBoundary(address) - WORD_LENGTH_BYTES);
	}

	@SuppressWarnings("unused")
	private void printBin(int num) {
		int count = 0;
		for (int i = count = 0; i < WORD_LENGTH_BITS; i++, num <<= 1)
			System.out.print((((num & (1 << WORD_LENGTH_BITS)) != 0) ? "1" : "0") +
					((++count % 4 == 0) ? " " : ""));
		System.out.print("\n");
	}

	@Override
	protected void reset() {
		if (debugBackStepper) {
			System.out.flush();
			System.err.println("ResetAction");
			System.err.flush();
		}
		getStackData();
		spDataIndex = getTableIndex(getSpValue());
		table.repaint();
	}

	@Override
	protected JComponent getHelpComponent() {
		// TODO write a proper help component
		final String helpContent = "Stack Visualizer\n\n"
				+ "Release: " + versionID + "   (" + releaseDate + ")\n\n"
				+ "Developed by George Z. Zachos (gzachos@cse.uoi.gr) and\n"
				+ "Petros Manousis (pmanousi@cse.uoi.gr) under the supervision of\n"
				+ "Aristides (Aris) Efthymiou (efthym@cse.uoi.gr).\n\n"
				+ "About\n"
				+ "This tool allows the user to view in real time the memory modification operations\n"
				+ "taking place in the stack segment. The user can also observe how the stack grows.\n"
				+ "The address pointed by the stack pointer is displayed in a yellow background\n"
				+ "(currently word-aligned) while lower addresses have a light grey background (given\n"
				+ "that stack growth takes place form higher to lower addresses).\n\n"
				+ "Note\n"
				+ "This program is suposed to be used as a MARS Tool and NOT as a stand-alone application."
				+ "\n\n"
				+ "Contact\n"
				+ "For questions or comments contact: George Z. Zachos (gzachos@cse.uoi.gr) or\n"
				+ "Aristides Efthymiou (efthym@cse.uoi.gr)";
		JButton help = new JButton("Help");
		help.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				showMessageWindow(helpContent);
			}
		});
		return help;
	}
	
	private void showMessageWindow(String message) {
		JOptionPane.showMessageDialog(theWindow, message);
	}

}
