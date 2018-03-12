package mars.tools;

import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.util.Observable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import mars.ProgramStatement;
import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;
import mars.mips.hardware.RegisterAccessNotice;
import mars.mips.hardware.RegisterFile;
import mars.mips.instructions.Instruction;

@SuppressWarnings({ "serial", "deprecation" })
public class StackVisualizer extends AbstractMarsToolAndApplication {

	private static String name    = "Stack Visualizer";
	private static String version = "Version 0.1 (George Z. Zachos, Petros Manousis)";
	private static String heading = "Visualizing stack modification operations";

	private static int       spRegNumber  = RegisterFile.STACK_POINTER_REGISTER;
	private static final int SP_INIT_ADDR = Memory.stackPointer;
	private static Memory    memInstance  = Memory.getInstance();
	private static boolean   endianness   = memInstance.getByteOrder();
	
	private static int         TABLECELLS_PER_ROW = 4;
	private static final int   NUMBER_OF_COLUMNS  = TABLECELLS_PER_ROW + 2;
	private static int         numRows  = 16;
	private static Object[][]  data     = new Object[numRows][NUMBER_OF_COLUMNS];;
	private static String[]    colNames = {"Address", "-0", "-1", "-2", "-3", "Reg"};
	private static JTable      table;
	private static JPanel      panel;
	private static JScrollPane scrollPane;
	private static DefaultTableModel tableModel;
	private static JTextField  spField;
	private static final int   INSTRUCTION_LENGTH_BITS = Instruction.INSTRUCTION_LENGTH_BITS;
	private static final int   STORED_REG_COL = 5;
	
	private static String regNameToBeStoredInStack = null;
	
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
		getStackData();
		return panel;
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
		/*
		 * TODO verify memory address arithmetic.
		 * Take into account endianness and stack growth.
		 */
		System.out.println("getStackData start");
		/*
		 * Initial value of spAddr is 0x7FFFEFFC = 2147479548.
		 * We ignore the word starting @0x7FFFEFFC, hence the
		 * first 4 bytes (1 word) to be displayed are:
		 * 0x7FFFEFFB, 0x7FFFEFFA, 0x7FFFEFF9, 0x7FFFEFF8 or in decimal value:
		 * 2147479547, 2147479546, 2147479545, 2147479544.
		 */
		for (int row = 0, addr = SP_INIT_ADDR-1; row < numRows; row++)
		{
			data[row][0] = "0x" + hex(addr);
			try {
				/* 
				 * memInstance.getRawWord(addr-3);
				 * TODO Use in order to display whole word in a table cell. 
				 */
				for (int j = 1; j < (NUMBER_OF_COLUMNS-1); j++) {
					/*
					 * Endianness determines whether byte position in value and
					 * byte position in memory match.
					 */
					col = (endianness == Memory.LITTLE_ENDIAN) ? j : (NUMBER_OF_COLUMNS-1)-j;
//					System.out.println("(" + row + "," + j + ") - "
//							+ addr +": " + memInstance.getByte(addr));
//					System.out.println("(" + row + "," + col + ") - "
//							+ addr +": " + hex(memInstance.getByte(addr)));
					data[row][col] = hex(memInstance.getByte(addr--));
				}
				System.out.println(data[row][0] + ": " + data[row][1] + "," +
						data[row][2] + "," + data[row][3] + "," + data[row][4] +
						" (" + data[row][5] + ")");
/*				System.out.println(data[row][0] + ": " + hex((int)data[row][1]) + ","
						+ hex((int)data[row][2]) + "," + hex((int)data[row][3]) + ","
						+ hex((int)data[row][4]));*/
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
		addAsObserver(RegisterFile.getRegisters()[spRegNumber]);
		addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
	}
	
	@Override
	protected void processMIPSUpdate(Observable resource, AccessNotice notice) {

//		System.out.println(notice.accessIsFromMIPS() +" " + notice.accessIsFromGUI());

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
			RegisterAccessNotice r = (RegisterAccessNotice) notice;
			if (r.getAccessType() == AccessNotice.READ)
				return;
			System.out.println("\nRegisterAccessNotice (W): " + r.getRegisterName()
					+ " value: " + getSpValue());
		}
	}
	
	private void processStackMemoryUpdate(MemoryAccessNotice notice) {
		if (notice.getAccessType() == AccessNotice.READ)
			return;
		String regName;
		if (regNameToBeStoredInStack != null) {
			regName = regNameToBeStoredInStack;
			regNameToBeStoredInStack = null;
		} else
			regName = "";
		System.out.println("\nStackAccessNotice (" + 
				((notice.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
				+ notice.getAddress() + " value: " + notice.getValue() +
				" (stored: " + regName + ")");
//		System.out.println((SP_INIT_ADDR - notice.getAddress()) + " " +
//				Memory.alignToWordBoundary(SP_INIT_ADDR - notice.getAddress()));
		int row = (Memory.alignToWordBoundary(SP_INIT_ADDR - notice.getAddress()) / 4)-1;
		data[row][STORED_REG_COL] = regName;
		getStackData();
	}
	
	private void processTextMemoryUpdate(MemoryAccessNotice notice) {
		if (notice.getAccessType() == AccessNotice.WRITE)
			return;
		System.out.println("\nTextAccessNotice (" + 
				((notice.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
				+ notice.getAddress() + " value: " + notice.getValue() /*+ " = "*/);
		//printBin(notice.getValue());
		try {
			ProgramStatement stmnt =  memInstance.getStatementNoNotify(notice.getAddress());
			Instruction instr = stmnt.getInstruction();
			String instrName = instr.getName();
			int[] operands;
			if (isStoreInstruction(instrName)) {
				System.out.println("stmnt: " + stmnt.getPrintableBasicAssemblyStatement());
				operands = stmnt.getOperands();
/*				for (int i = 0; i < operands.length; i++)
					System.out.print(operands[i] + " ");
				System.out.println();
*/
				regNameToBeStoredInStack = RegisterFile.getRegisters()[operands[0]].getName();
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
	
	private String hex(int decimalValue) {
		return Integer.toHexString(decimalValue);
	}

	private int getSpValue() {
		return RegisterFile.getValue(spRegNumber);
	}
	
	private void printBin(int num) {
		int count = 0;
		for (int i = count = 0; i < 32; i++, num <<= 1)
			System.out.print((((num & 0x80000000) != 0) ? "1" : "0") +
					((++count % 4 == 0) ? " " : ""));
		System.out.print("\n");
	}
	
	@Override
	protected void reset() {
		/* Reset the column holding the register name whose contents
		 * were stored in the corresponding memory address.
		 */
		for (int i = 0; i < data.length; i++)
			data[i][STORED_REG_COL] = null;
	}
	
}
