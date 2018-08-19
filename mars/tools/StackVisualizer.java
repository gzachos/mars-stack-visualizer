package mars.tools;

import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Observable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
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

	private static String name    = "Stack Visualizer";
	private static String version = "Version 0.1 (George Z. Zachos, Petros Manousis)";
	private static String heading = "Visualizing stack modification operations";

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

	private static final int   WORD_LENGTH_BYTES        = Memory.WORD_LENGTH_BYTES;
	private static final int   WORD_LENGTH_BITS         = WORD_LENGTH_BYTES * 8;
	// data[][] related fields
	private static final int   NUMBER_OF_COLUMNS        = WORD_LENGTH_BYTES + 2; // +1 for address
	                                                                             // +1 for register name stored
	private static final int   ADDRESS_COLUMN           = 0; // Should always be in first column.
	private static final int   FIRST_BYTE_COLUMN        = 1; // Should always be in second column.
	private static final int   LAST_BYTE_COLUMN         = FIRST_BYTE_COLUMN + WORD_LENGTH_BYTES - 1;
	private static final int   STORED_REGISTER_COLUMN   = LAST_BYTE_COLUMN + 1;
	private static final int   RS_OPERAND_LIST_INDEX    = 0;
	private static int         numRows                  = 16; // TODO make generic
	private static final int   MAX_SP_VALUE             = Memory.stackBaseAddress + (WORD_LENGTH_BYTES-1);
	private static int         maxSpValue               = SP_INIT_ADDR - 1;
	private static int         maxSpValueWordAligned    = SP_INIT_ADDR - WORD_LENGTH_BYTES;
	private static int         minSpValue               = MAX_SP_VALUE;
	private static Object[][]  data                     = new Object[numRows][NUMBER_OF_COLUMNS];
	private static String      regNameToBeStoredInStack = null;

	// GUI-Related fields
	@SuppressWarnings("unused")
	private static String[]    colNames = {"Address", "-0", "-1", "-2", "-3", "Reg"};
	private static JTable      table;
	private static JPanel      panel;
	private static JScrollPane scrollPane;
	@SuppressWarnings("unused")
	private static DefaultTableModel tableModel;
	private static JTextField  spField;

	private static ArrayList<?> textSymbols = null; // TODO verify generics
	private static ArrayList<Integer> jumpAddresses = new ArrayList<Integer>();
	private static boolean disabledBackStep = false;

	private static boolean debug = false;

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
		panel = new JPanel(new GridBagLayout());
		panel.setPreferredSize(new Dimension(600, 650));
		spField = new JTextField("Stack Pointer Value", 10);
		spField.setEditable(false);
		panel.add(spField);
		table = new JTable(new DefaultTableModel());
		table.setEnabled(false);
		tableModel = (DefaultTableModel) table.getModel();
		scrollPane = new JScrollPane(table);
		panel.add(scrollPane);
		return panel;
	}

	@Override
	protected void initializePreGUI() {

		marsGui.getAssembleButton().addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				System.out.flush();
				System.err.println("Assemble");
				System.err.flush();
				textSymbols = null;
				disableBackStepper(); // Not enough to work
				jumpAddresses.clear();
				/* Reset the column holding the register name whose contents
				 * were stored in the corresponding memory address.
				 */
				for (int i = 0; i < data.length; i++)
					data[i][STORED_REGISTER_COLUMN] = null;

				// TODO verify
				marsGui.getRunButton().setEnabled(true);
				marsGui.getStepButton().setEnabled(true);
			}
		});

		marsGui.getRunButton().addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				disableBackStepper();
			}
		});

		marsGui.getStepButton().addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				disableBackStepper();
			}
		});

		getStackData();
	}

	@Override
	protected void initializePostGUI() {
		connectButton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				if (connectButton.isConnected()) {
					restoreBackStepper(); // TODO We really need this?
					disabledBackStep = false;
				} else {
					// TODO verify
					/*
					 * User program should be recompiled (and executed) after
					 * StackVisualizer is launched. This is required for
					 * coherently storing the subroutine call stack.
					 */
					marsGui.getRunButton().setEnabled(false);
					marsGui.getStepButton().setEnabled(false);
				}
			}
		});
	}

	/*
	 * getStackData() fires a MemoryAccessNotice every time
	 * it reads from memory. For this reason it should not be
	 * called in a code block handling a MemoryAccessNotice
	 * of AccessNotice.READ type as it will lead in infinite
	 * recursive calls of itself.
	 */
	protected void getStackData() {
		int col;

		System.out.println("getStackData start");
		/*
		 * Initial value of spAddr is 0x7FFFEFFC = 2147479548 (Default MIPS memory configuration).
		 * We ignore the word starting @0x7FFFEFFC, hence the
		 * first 4 bytes (1 word) to be displayed are:
		 * 0x7FFFEFFB, 0x7FFFEFFA, 0x7FFFEFF9, 0x7FFFEFF8 or in decimal value:
		 * 2147479547, 2147479546, 2147479545, 2147479544.
		 */
		for (int row = 0, addr = maxSpValue; row < numRows; row++)
		{
			data[row][ADDRESS_COLUMN] = "0x" + hex(addr);
			try {
				// TODO Allow 'whole word' or 'per byte' data display.
				for (int j = FIRST_BYTE_COLUMN; j <= LAST_BYTE_COLUMN; j++) {
					/*
					 * Endianness determines whether byte position in value and
					 * byte position in memory match.
					 */
					col = (endianness == LITTLE_ENDIAN) ? j : (LAST_BYTE_COLUMN-j) + FIRST_BYTE_COLUMN;
					if (displayDataPerByte)
						System.out.println("(" + row + "," + col + ") - "
								+ addr +": " + hex(memInstance.getByte(addr)));
					data[row][col] = hex(memInstance.getByte(addr--));
				}
				System.out.print(data[row][0] + ": ");
				for (int i = FIRST_BYTE_COLUMN; i <= LAST_BYTE_COLUMN; i++)
					System.out.print(data[row][i] + (i == LAST_BYTE_COLUMN ? "" : ","));
				System.out.println(" (" + data[row][STORED_REGISTER_COLUMN] + ")");
			} catch (AddressErrorException aee) {
				aee.printStackTrace();
			}
		}
		// TODO decide call sequence
/*		tableModel.setDataVector(data, colNames);
		tableModel.fireTableDataChanged();
		// spField.setText(String.valueOf(getSpValue()));
		spField.setText(hex(getSpValue()));
*/		System.out.println("getStackData end\n");
	}

	@Override
	protected void addAsObserver() {
		addAsObserver(Memory.stackLimitAddress, Memory.stackBaseAddress);
		addAsObserver(RegisterFile.getRegisters()[SP_REG_NUMBER]);
		addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
	}


	@Override
	protected void processMIPSUpdate(Observable resource, AccessNotice notice) {

		// System.out.println(notice.accessIsFromMIPS() +" " + notice.accessIsFromGUI());

		if (textSymbols == null) { // TODO fire after assemble
			textSymbols = Globals.program.getLocalSymbolTable().getTextSymbols();
			//System.out.println(textSymbols.toString() + " " + textSymbols.size());
			for (int i = 0; i < textSymbols.size(); i++) {
				Symbol s = (Symbol) textSymbols.get(i);
				System.out.println(s.getName() + " - " + s.getAddress());
			}
		}

		if (!notice.accessIsFromMIPS())
			return;

		if (notice instanceof MemoryAccessNotice) {
			MemoryAccessNotice m = (MemoryAccessNotice) notice;
			if (Memory.inTextSegment(m.getAddress()))
				processTextMemoryUpdate(m);
			else
				processStackMemoryUpdate(m);
		}
		else if (notice instanceof RegisterAccessNotice) {
			// Currently only $sp is observed
			RegisterAccessNotice r = (RegisterAccessNotice) notice;
			if (r.getAccessType() == AccessNotice.READ)
				return;
			if (debug)
				System.out.println("\nRegisterAccessNotice (W): " + r.getRegisterName()
						+ " value: " + getSpValue());
			if (r.getRegisterName().equals("$sp"))
				if (minSpValue > getSpValue())
					minSpValue = getSpValue();
		}
	}

	private void processStackMemoryUpdate(MemoryAccessNotice notice) {
		String regName;

		if (notice.getAccessType() == AccessNotice.READ)
			return;

		if (regNameToBeStoredInStack != null) {
			regName = regNameToBeStoredInStack;
			regNameToBeStoredInStack = null;
		} else
			regName = "";
		if (debug) {
			System.out.println("\nStackAccessNotice (" +
					((notice.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
					+ notice.getAddress() + " value: " + notice.getValue() +
					" (stored: " + regName + ")");
		}
		int row = (maxSpValueWordAligned - alignToCurrentWordBoundary(notice.getAddress())) / WORD_LENGTH_BYTES;
		data[row][STORED_REGISTER_COLUMN] = regName;
		getStackData();
	}

	private void processTextMemoryUpdate(MemoryAccessNotice notice) {
		if (notice.getAccessType() == AccessNotice.WRITE)
			return;
		if (debug) {
			System.out.println("\nTextAccessNotice (" +
					((notice.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
					+ notice.getAddress() + " value: " + notice.getValue() /*+ " = "*/);
		}
		// printBin(notice.getValue());
		try {
			ProgramStatement stmnt =  memInstance.getStatementNoNotify(notice.getAddress());
			Instruction instr = stmnt.getInstruction();
			String instrName = instr.getName();
			int[] operands;
			if (isStoreInstruction(instrName)) {
				if (debug)
					System.out.println("Statement TBE: " + stmnt.getPrintableBasicAssemblyStatement());

				operands = stmnt.getOperands();
	/*			for (int i = 0; i < operands.length; i++)
					System.out.print(operands[i] + " ");
				System.out.println();
	*/
				regNameToBeStoredInStack = RegisterFile.getRegisters()[operands[RS_OPERAND_LIST_INDEX]].getName();
			}
			else if (isJumpInstruction(instrName) || isJumpAndLinkInstruction(instrName)) {
				int targetAdrress = stmnt.getOperand(0)*4;
				String targetLabel = addrToTextSymbol(targetAdrress);
				if (isJumpAndLinkInstruction(instrName))
					jumpAddresses.add(stmnt.getAddress());
				if (targetLabel != null) {
					System.out.print("Jumping to: " + targetLabel);
					if (isJumpAndLinkInstruction(instrName))
						System.out.println(" (" + (jumpAddresses.size()) + ")");
				}
			}
			else if (isJumpRegInstruction(instrName)) {
				int targetRegister = stmnt.getOperand(0);
				Register reg = RegisterFile.getRegisters()[targetRegister];
				int targetAddress = reg.getValue();
				ProgramStatement callerStatement =  memInstance.getStatementNoNotify(targetAddress-4);
				System.out.println("Returning from: " + addrToTextSymbol(callerStatement.getOperand(0)*4) +
						" (" +jumpAddresses.size() + ") to line: " + callerStatement.getSourceLine());
			//	System.out.println(jumpAddresses.get(jumpAddresses.size()-1) + " == " + callerStatement.getAddress());
			//	for (int i = 0; i< jumpAddresses.size(); i++)
			//		System.out.println((i+1) + ": " + jumpAddresses.get(i));

				try {
					if (jumpAddresses.remove(jumpAddresses.size()-1) != callerStatement.getAddress())
						System.out.println("Mismatching return address");
				} catch (IndexOutOfBoundsException e) {
					// FIXME Exception thrown whenever function calling steps are undone
					// and the program is again executed. undo last step is not supported
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

	private String addrToTextSymbol(int addr) {
		if (textSymbols == null) {
			// textSymbols = Globals.program.getLocalSymbolTable().getTextSymbols(); // no return in this case
			return null;
		}

		for (int i = 0; i < textSymbols.size(); i++) {
			Symbol s = (Symbol) textSymbols.get(i);
			if (s.getAddress() == addr)
				return s.getName();
		}
		return null;
	}

	private String hex(int decimalValue) {
		return Integer.toHexString(decimalValue);
	}

	private int getSpValue() {
		return RegisterFile.getValue(SP_REG_NUMBER);
	}

	private boolean disableBackStepper() {
		BackStepper bs = Globals.program.getBackStepper();
		if (!isObserving() || bs == null)
			return false;
		if (bs.enabled()) {
			System.err.println("disabled backStepper");
			bs.setEnabled(false);
			disabledBackStep = true;
		}
		return true;
	}

	public void restoreBackStepper() {
		System.err.println("dsbs: " + disabledBackStep);
		if (disabledBackStep) {
			BackStepper bs = Globals.program.getBackStepper();
			if (bs != null) {
				System.err.println("enabled backStepper");
				bs.setEnabled(true);
			}
		}
	}

	@Override
	protected void performSpecialClosingDuties() {
		disabledBackStep = false;
	}

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
		System.out.flush();
		System.err.println("ResetAction");
		System.err.flush();
		// TODO Do we really need it?
	}

}
